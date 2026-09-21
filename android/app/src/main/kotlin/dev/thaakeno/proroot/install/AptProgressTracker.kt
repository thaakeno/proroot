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
    private val fileCount = Regex("""file\s+(\d+)\s+of\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val remaining = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?\s+remaining""", RegexOption.IGNORE_CASE)

    private var totalBytes = 0L
    private var totalItems = 0
    private var lastBytes = 0L
    private var lastSampleNanos = System.nanoTime()
    private var speedBytesPerSecond = 0L

    fun accept(line: String): AptProgressSnapshot? {
        parseArchiveTotal(line)

        if (line.startsWith("dlstatus:")) {
            val parts = line.split(':', limit = 4)
            if (parts.size < 4) return null

            val completed = parts[1].trim().toIntOrNull() ?: 0
            val percent = parts[2].trim().toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: return null
            val detail = parts[3].trim()
            val counts = fileCount.find(detail)
            val reportedTotal = counts?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
            if (reportedTotal > 0) totalItems = reportedTotal

            val downloaded = if (totalBytes > 0L) {
                (totalBytes * (percent / 100.0)).roundToLong().coerceIn(0L, totalBytes)
            } else {
                0L
            }
            updateSpeed(downloaded)

            return AptProgressSnapshot(
                phase = AptProgressPhase.downloading,
                fraction = percent / 100.0,
                detail = detail,
                downloadedBytes = downloaded,
                totalBytes = totalBytes,
                speedBytesPerSecond = speedBytesPerSecond,
                completedItems = completed,
                totalItems = totalItems,
                etaSeconds = parseEta(detail),
            )
        }

        if (line.startsWith("pmstatus:")) {
            val parts = line.split(':', limit = 4)
            if (parts.size < 4) return null

            val percent = parts[2].trim().toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: return null
            val detail = parts[3].trim()
            val completed = if (totalItems > 0) {
                ((percent / 100.0) * totalItems).toInt().coerceIn(0, totalItems)
            } else {
                0
            }

            return AptProgressSnapshot(
                phase = AptProgressPhase.installing,
                fraction = percent / 100.0,
                detail = detail,
                downloadedBytes = totalBytes,
                totalBytes = totalBytes,
                speedBytesPerSecond = 0L,
                completedItems = completed,
                totalItems = totalItems,
                etaSeconds = null,
            )
        }

        return null
    }

    private fun parseArchiveTotal(line: String) {
        val match = needBytes.find(line) ?: return
        val value = match.groupValues[1].toDoubleOrNull() ?: return
        val unit = match.groupValues[2]
        totalBytes = when (unit) {
            "kB" -> (value * 1_000.0).roundToLong()
            "MB" -> (value * 1_000_000.0).roundToLong()
            "GB" -> (value * 1_000_000_000.0).roundToLong()
            "TB" -> (value * 1_000_000_000_000.0).roundToLong()
            "B" -> value.roundToLong()
            else -> totalBytes
        }
    }

    private fun updateSpeed(currentBytes: Long) {
        val now = System.nanoTime()
        val elapsed = (now - lastSampleNanos) / 1_000_000_000.0
        if (elapsed < 0.35) return

        speedBytesPerSecond = ((currentBytes - lastBytes) / elapsed)
            .roundToLong()
            .coerceAtLeast(0L)
        lastBytes = currentBytes
        lastSampleNanos = now
    }

    private fun parseEta(detail: String): Long? {
        val match = remaining.find(detail) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: 0L
        val seconds = match.groupValues[3].toLongOrNull() ?: 0L
        val total = hours * 3600L + minutes * 60L + seconds
        return total.takeIf { it > 0L }
    }
}
