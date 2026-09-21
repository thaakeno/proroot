package dev.thaakeno.proroot.install

import kotlin.math.roundToLong

enum class AptProgressPhase {
    downloading,
    installing,
}

data class AptProgressSnapshot(
    val phase: AptProgressPhase,
    val fraction: Double,
    val detail: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSecond: Long,
    val completedItems: Int,
    val totalItems: Int,
    val etaSeconds: Long?,
)

class AptProgressTracker {
    private val needBytes = Regex("""Need to get\s+([0-9.]+)\s+([kMGT]?B)""")
    private val getLine = Regex("""^Get:(\d+)\s+.*\[([0-9.]+)\s+([kMGT]?B)\]$""")
    private val fetchedLine = Regex(
        """^Fetched\s+([0-9.]+)\s+([kMGT]?B)\s+in\s+(.+?)(?:\s+\(([^)]+)\))?$""",
    )
    private val packagePlan = Regex(
        """(\d+) upgraded,\s+(\d+) newly installed,\s+(\d+) to remove""",
    )
    private val unpacking = Regex("""^Unpacking\s+([^\s]+)""")
    private val settingUp = Regex("""^Setting up\s+([^\s]+)""")
    private val processingTriggers = Regex("""^Processing triggers for\s+([^\s]+)""")

    private var totalBytes = 0L
    private var downloadedBytes = 0L
    private var lastBytes = 0L
    private var lastSampleNanos = System.nanoTime()
    private var speedBytesPerSecond = 0L

    private var totalPackages = 0
    private val unpackedPackages = linkedSetOf<String>()
    private val configuredPackages = linkedSetOf<String>()

    fun accept(line: String): AptProgressSnapshot? {
        parseArchiveTotal(line)
        parsePackagePlan(line)

        getLine.matchEntire(line)?.let { match ->
            val item = match.groupValues[1].toIntOrNull() ?: 0
            val bytes = parseBytes(match.groupValues[2], match.groupValues[3])
            if (bytes > 0L) {
                downloadedBytes = (downloadedBytes + bytes)
                    .coerceAtMost(totalBytes.takeIf { it > 0L } ?: Long.MAX_VALUE)
                updateSpeed(downloadedBytes)
            }

            return AptProgressSnapshot(
                phase = AptProgressPhase.downloading,
                fraction = downloadFraction(),
                detail = line.substringBeforeLast(" [").removePrefix("Get:$item ").trim(),
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                speedBytesPerSecond = speedBytesPerSecond,
                completedItems = item,
                totalItems = 0,
                etaSeconds = downloadEtaSeconds(),
            )
        }

        fetchedLine.matchEntire(line)?.let { match ->
            val fetched = parseBytes(match.groupValues[1], match.groupValues[2])
            if (fetched > 0L) {
                downloadedBytes = if (totalBytes > 0L) totalBytes else fetched
            }
            updateSpeed(downloadedBytes)

            return AptProgressSnapshot(
                phase = AptProgressPhase.downloading,
                fraction = 1.0,
                detail = line,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes.takeIf { it > 0L } ?: downloadedBytes,
                speedBytesPerSecond = speedBytesPerSecond,
                completedItems = 0,
                totalItems = 0,
                etaSeconds = 0,
            )
        }

        unpacking.find(line)?.let { match ->
            unpackedPackages += normalizePackage(match.groupValues[1])
            return installSnapshot(line)
        }

        settingUp.find(line)?.let { match ->
            configuredPackages += normalizePackage(match.groupValues[1])
            return installSnapshot(line)
        }

        processingTriggers.find(line)?.let {
            return installSnapshot(line)
        }

        return null
    }

    private fun parseArchiveTotal(line: String) {
        val match = needBytes.find(line) ?: return
        totalBytes = parseBytes(match.groupValues[1], match.groupValues[2])
        downloadedBytes = 0L
        lastBytes = 0L
        lastSampleNanos = System.nanoTime()
        speedBytesPerSecond = 0L
    }

    private fun parsePackagePlan(line: String) {
        val match = packagePlan.find(line) ?: return
        val upgraded = match.groupValues[1].toIntOrNull() ?: 0
        val installed = match.groupValues[2].toIntOrNull() ?: 0
        totalPackages = upgraded + installed
    }

    private fun installSnapshot(detail: String): AptProgressSnapshot {
        val fraction = if (totalPackages > 0) {
            ((unpackedPackages.size + configuredPackages.size).toDouble() /
                (totalPackages * 2.0)).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val completed = configuredPackages.size.coerceAtMost(totalPackages.takeIf { it > 0 } ?: Int.MAX_VALUE)

        return AptProgressSnapshot(
            phase = AptProgressPhase.installing,
            fraction = fraction,
            detail = detail,
            downloadedBytes = totalBytes.takeIf { it > 0L } ?: downloadedBytes,
            totalBytes = totalBytes,
            speedBytesPerSecond = 0L,
            completedItems = completed,
            totalItems = totalPackages,
            etaSeconds = null,
        )
    }

    private fun downloadFraction(): Double =
        if (totalBytes > 0L) {
            downloadedBytes.toDouble().div(totalBytes).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

    private fun downloadEtaSeconds(): Long? {
        if (totalBytes <= 0L || speedBytesPerSecond <= 0L) return null
        return ((totalBytes - downloadedBytes).coerceAtLeast(0L) / speedBytesPerSecond)
            .takeIf { it > 0L }
    }

    private fun updateSpeed(currentBytes: Long) {
        val now = System.nanoTime()
        val elapsed = (now - lastSampleNanos) / 1_000_000_000.0
        if (elapsed < 0.25) return

        speedBytesPerSecond = ((currentBytes - lastBytes) / elapsed)
            .roundToLong()
            .coerceAtLeast(0L)
        lastBytes = currentBytes
        lastSampleNanos = now
    }

    private fun parseBytes(value: String, unit: String): Long {
        val number = value.toDoubleOrNull() ?: return 0L
        return when (unit) {
            "kB" -> (number * 1_000.0).roundToLong()
            "MB" -> (number * 1_000_000.0).roundToLong()
            "GB" -> (number * 1_000_000_000.0).roundToLong()
            "TB" -> (number * 1_000_000_000_000.0).roundToLong()
            "B" -> number.roundToLong()
            else -> 0L
        }
    }

    private fun normalizePackage(raw: String): String =
        raw.trim().removeSuffix(":").substringBefore("(")
}
