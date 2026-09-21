package dev.thaakeno.proroot.install

import dev.thaakeno.proroot.runtime.RuntimePhase
import dev.thaakeno.proroot.runtime.RuntimeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
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
    private val concurrency = Semaphore(4)
    private val lock = Any()
    private val progressById = mutableMapOf<String, Long>()
    private var lastSampleNanos = System.nanoTime()
    private var lastSampleBytes = 0L
    private var lastSpeed = 0L

    suspend fun downloadAll(
        assets: List<RuntimeAsset>,
        onStatus: (RuntimeStatus) -> Unit,
    ): Map<RuntimeAssetKind, File> = coroutineScope {
        cacheDir.mkdirs()
        val total = assets.sumOf { it.size }

        synchronized(lock) {
            assets.forEach { asset -> progressById[asset.id] = existingBytes(asset) }
            lastSampleBytes = progressById.values.sum()
            lastSampleNanos = System.nanoTime()
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
        val partial = File(cacheDir, asset.fileName + ".part")
        return partial.length().coerceAtMost(asset.size)
    }

    private fun download(
        asset: RuntimeAsset,
        totalAll: Long,
        onStatus: (RuntimeStatus) -> Unit,
    ): File {
        val target = asset.cacheFile(cacheDir)
        if (Hashing.verify(target, asset.sha256)) {
            updateProgress(asset, asset.size, totalAll, onStatus)
            return target
        }
        target.delete()

        val partial = File(cacheDir, asset.fileName + ".part")
        var offset = partial.length().coerceAtMost(asset.size)
        if (partial.length() != offset) {
            RandomAccessFile(partial, "rw").use { it.setLength(offset) }
        }

        val request = Request.Builder()
            .url(asset.url)
            .apply {
                if (offset > 0) header("Range", "bytes=$offset-")
            }
            .build()

        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "Download failed for ${asset.id}: HTTP ${response.code}"
            }

            if (offset > 0 && response.code != 206) {
                offset = 0
                partial.delete()
            }

            val body = response.body ?: error("Empty response for ${asset.id}")
            RandomAccessFile(partial, "rw").use { output ->
                output.seek(offset)
                body.byteStream().buffered(256 * 1024).use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var written = offset
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        updateProgress(asset, written, totalAll, onStatus)
                    }
                }
                output.fd.sync()
            }
        }

        check(partial.length() == asset.size) {
            "Size mismatch for ${asset.id}: got ${partial.length()}, expected ${asset.size}"
        }
        check(Hashing.verify(partial, asset.sha256)) {
            "SHA-256 mismatch for ${asset.id}"
        }
        check(partial.renameTo(target)) { "Could not finalize ${asset.fileName}" }
        updateProgress(asset, asset.size, totalAll, onStatus)
        return target
    }

    private fun updateProgress(
        asset: RuntimeAsset,
        bytes: Long,
        total: Long,
        onStatus: (RuntimeStatus) -> Unit,
    ) {
        val status = synchronized(lock) {
            progressById[asset.id] = bytes.coerceIn(0, asset.size)
            val now = System.nanoTime()
            val current = progressById.values.sum()
            val elapsed = (now - lastSampleNanos) / 1_000_000_000.0
            if (elapsed >= 0.25) {
                lastSpeed = ((current - lastSampleBytes) / elapsed).toLong().coerceAtLeast(0)
                lastSampleNanos = now
                lastSampleBytes = current
            }
            RuntimeStatus(
                phase = RuntimePhase.downloading,
                progress = if (total == 0L) 0.0 else current.toDouble() / total,
                message = "Downloading Linux components",
                downloadedBytes = current,
                totalBytes = total,
                speedBytesPerSecond = lastSpeed,
            )
        }
        onStatus(status)
    }
}
