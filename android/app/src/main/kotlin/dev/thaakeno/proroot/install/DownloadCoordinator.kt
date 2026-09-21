package dev.thaakeno.proroot.install

import dev.thaakeno.proroot.runtime.RuntimePhase
import dev.thaakeno.proroot.runtime.RuntimeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class DownloadCoordinator(
    private val cacheDir: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) {
    companion object {
        private const val MAX_ATTEMPTS = 4
        private const val BUFFER_SIZE = 256 * 1024
        private const val UI_EMIT_INTERVAL_NANOS = 250_000_000L
        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
    }

    private val concurrency = Semaphore(4)
    private val lock = Any()
    private val progressById = mutableMapOf<String, Long>()
    private val sizeById = mutableMapOf<String, Long>()
    private var lastSampleNanos = System.nanoTime()
    private var lastSampleBytes = 0L
    private var lastSpeed = 0L
    private var lastEmitNanos = 0L

    suspend fun downloadAll(
        assets: List<RuntimeAsset>,
        onStatus: (RuntimeStatus) -> Unit,
    ): Map<RuntimeAssetKind, File> = coroutineScope {
        cacheDir.mkdirs()
        val total = assets.sumOf { it.size }

        synchronized(lock) {
            progressById.clear()
            sizeById.clear()
            assets.forEach { asset ->
                progressById[asset.id] = existingBytes(asset)
                sizeById[asset.id] = asset.size
            }
            lastSampleBytes = progressById.values.sum()
            lastSampleNanos = System.nanoTime()
            lastSpeed = 0L
            lastEmitNanos = 0L
        }

        assets.map { asset ->
            async(Dispatchers.IO) {
                concurrency.withPermit {
                    asset.kind to download(asset, total, onStatus)
                }
            }
        }.awaitAll().toMap()
    }

    private fun existingBytes(asset: RuntimeAsset): Long {
        val completed = asset.cacheFile(cacheDir)
        if (Hashing.verify(completed, asset.sha256)) return asset.size
        val partial = partialFile(asset)
        return partial.length().coerceAtMost(asset.size)
    }

    private suspend fun download(
        asset: RuntimeAsset,
        totalAll: Long,
        onStatus: (RuntimeStatus) -> Unit,
    ): File {
        val target = asset.cacheFile(cacheDir)
        if (Hashing.verify(target, asset.sha256)) {
            updateProgress(asset, asset.size, totalAll, onStatus, force = true)
            return target
        }
        target.delete()

        var lastFailure: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return downloadAttempt(asset, totalAll, onStatus)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                lastFailure = failure

                val partial = partialFile(asset)
                if (partial.length() > asset.size || failure is ChecksumMismatch) {
                    partial.delete()
                    updateProgress(asset, 0L, totalAll, onStatus, force = true)
                }

                if (attempt == MAX_ATTEMPTS - 1) throw failure
                delay(backoffMillis(attempt))
            }
        }
        throw lastFailure ?: error("Download failed for ${asset.id}")
    }

    private fun downloadAttempt(
        asset: RuntimeAsset,
        totalAll: Long,
        onStatus: (RuntimeStatus) -> Unit,
    ): File {
        val target = asset.cacheFile(cacheDir)
        val partial = partialFile(asset)

        var offset = partial.length().coerceAtMost(asset.size)
        if (partial.length() != offset) {
            RandomAccessFile(partial, "rw").use { it.setLength(offset) }
        }

        if (offset == asset.size && Hashing.verify(partial, asset.sha256)) {
            finalizePartial(partial, target, asset)
            updateProgress(asset, asset.size, totalAll, onStatus, force = true)
            return target
        }
        if (offset == asset.size) {
            partial.delete()
            offset = 0L
            updateProgress(asset, 0L, totalAll, onStatus, force = true)
        }

        val request = Request.Builder()
            .url(asset.url)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 416 && offset > 0) {
                partial.delete()
                updateProgress(asset, 0L, totalAll, onStatus, force = true)
                error("Server rejected resume position for ${asset.id}")
            }
            check(response.isSuccessful) {
                "Download failed for ${asset.id}: HTTP ${response.code}"
            }

            val writeOffset = validateResumeResponse(response, asset, partial, offset, totalAll, onStatus)
            val body = response.body ?: error("Empty response for ${asset.id}")

            RandomAccessFile(partial, "rw").use { output ->
                output.seek(writeOffset)
                body.byteStream().buffered(BUFFER_SIZE).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = writeOffset
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        check(written <= asset.size) {
                            "Server sent too much data for ${asset.id}"
                        }
                        updateProgress(asset, written, totalAll, onStatus)
                    }
                }
                output.fd.sync()
            }
        }

        check(partial.length() == asset.size) {
            "Incomplete download for ${asset.id}: got ${partial.length()}, expected ${asset.size}"
        }
        if (!Hashing.verify(partial, asset.sha256)) {
            throw ChecksumMismatch("SHA-256 mismatch for ${asset.id}")
        }

        finalizePartial(partial, target, asset)
        updateProgress(asset, asset.size, totalAll, onStatus, force = true)
        return target
    }

    private fun validateResumeResponse(
        response: Response,
        asset: RuntimeAsset,
        partial: File,
        requestedOffset: Long,
        totalAll: Long,
        onStatus: (RuntimeStatus) -> Unit,
    ): Long {
        if (requestedOffset == 0L) {
            check(response.code == 200 || response.code == 206) {
                "Unexpected HTTP ${response.code} for ${asset.id}"
            }
            return 0L
        }

        if (response.code == 200) {
            RandomAccessFile(partial, "rw").use { it.setLength(0L) }
            updateProgress(asset, 0L, totalAll, onStatus, force = true)
            return 0L
        }

        check(response.code == 206) {
            "Server did not honor resume for ${asset.id}: HTTP ${response.code}"
        }

        val header = response.header("Content-Range")
            ?: error("Missing Content-Range while resuming ${asset.id}")
        val match = CONTENT_RANGE.matchEntire(header.trim())
            ?: error("Invalid Content-Range for ${asset.id}: $header")
        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].toLong()
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLong()

        check(start == requestedOffset) {
            "Resume offset mismatch for ${asset.id}: requested $requestedOffset, server returned $start"
        }
        check(end >= start) { "Invalid Content-Range end for ${asset.id}" }
        if (total != null) {
            check(total == asset.size) {
                "Remote size changed for ${asset.id}: $total != ${asset.size}"
            }
        }
        return requestedOffset
    }

    private fun finalizePartial(partial: File, target: File, asset: RuntimeAsset) {
        target.delete()
        check(partial.renameTo(target)) { "Could not finalize ${asset.fileName}" }
    }

    private fun partialFile(asset: RuntimeAsset): File =
        File(cacheDir, asset.fileName + ".part")

    private fun backoffMillis(attempt: Int): Long =
        750L * (1L shl attempt.coerceIn(0, 3))

    private fun updateProgress(
        asset: RuntimeAsset,
        bytes: Long,
        total: Long,
        onStatus: (RuntimeStatus) -> Unit,
        force: Boolean = false,
    ) {
        val status = synchronized(lock) {
            progressById[asset.id] = bytes.coerceIn(0, asset.size)
            val now = System.nanoTime()
            val current = progressById.values.sum()
            val elapsed = (now - lastSampleNanos) / 1_000_000_000.0

            if (elapsed >= 0.25) {
                lastSpeed = ((current - lastSampleBytes) / elapsed)
                    .toLong()
                    .coerceAtLeast(0)
                lastSampleNanos = now
                lastSampleBytes = current
            }

            val shouldEmit = force ||
                current >= total ||
                now - lastEmitNanos >= UI_EMIT_INTERVAL_NANOS
            if (!shouldEmit) {
                return@synchronized null
            }
            lastEmitNanos = now

            val completedItems = progressById.count { (id, value) ->
                value >= (sizeById[id] ?: Long.MAX_VALUE)
            }
            val fraction = if (total == 0L) 0.0 else current.toDouble() / total

            RuntimeStatus(
                phase = RuntimePhase.downloading,
                progress = fraction,
                message = "Downloading Linux components",
                downloadedBytes = current,
                totalBytes = total,
                speedBytesPerSecond = lastSpeed,
                stageProgress = fraction,
                stageDetail = "Downloading ${asset.fileName}",
                stageDownloadedBytes = current,
                stageTotalBytes = total,
                stageSpeedBytesPerSecond = lastSpeed,
                completedItems = completedItems,
                totalItems = progressById.size,
            )
        }
        status?.let(onStatus)
    }

    private class ChecksumMismatch(message: String) : IllegalStateException(message)
}
