package dev.thaakeno.proroot.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Read-only compatibility view for Linux /proc entries Android SELinux hides
 * from ordinary app UIDs. Values come only from public Android APIs or data
 * already visible to this app.
 */
class ProcCompatBridge(
    context: Context,
    private val paths: RuntimePaths,
) {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "proroot-proc-compat").apply { isDaemon = true }
    }

    private val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val pageSize = runCatching {
        Os.sysconf(OsConstants._SC_PAGESIZE).coerceAtLeast(4096L)
    }.getOrDefault(4096L)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        paths.procCompatDir.mkdirs()
        refresh()
        executor.scheduleAtFixedRate(
            { runCatching(::refresh) },
            1,
            1,
            TimeUnit.SECONDS,
        )
    }

    fun refresh() {
        paths.procCompatDir.mkdirs()

        val uptimeMs = SystemClock.elapsedRealtime().coerceAtLeast(1L)
        val uptimeSeconds = uptimeMs / 1000.0
        val clockTicks = runCatching {
            Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(1L)
        }.getOrDefault(100L)
        val processTicks = visibleProcessCpuTicks().takeIf { it > 0L }
            ?: ((Process.getElapsedCpuTime() * clockTicks) / 1000L)
        val totalTicks = ((uptimeMs * cpuCount * clockTicks) / 1000L).coerceAtLeast(1L)
        val clampedProcessTicks = processTicks.coerceIn(0L, totalTicks)
        val idleTicks = (totalTicks - clampedProcessTicks).coerceAtLeast(0L)

        val perCpuUser = clampedProcessTicks / cpuCount
        val perCpuIdle = idleTicks / cpuCount
        val bootTimeSeconds = (System.currentTimeMillis() / 1000L) - uptimeSeconds.toLong()

        val stat = buildString {
            appendLine("cpu $clampedProcessTicks 0 0 $idleTicks 0 0 0 0 0 0")
            repeat(cpuCount) { index ->
                appendLine("cpu$index $perCpuUser 0 0 $perCpuIdle 0 0 0 0 0 0")
            }
            appendLine("intr 0")
            appendLine("ctxt 0")
            appendLine("btime $bootTimeSeconds")
            appendLine("processes 1")
            appendLine("procs_running 1")
            appendLine("procs_blocked 0")
            appendLine("softirq 0")
        }

        val aggregateIdleSeconds = max(
            0.0,
            uptimeSeconds * cpuCount - clampedProcessTicks.toDouble() / clockTicks,
        )
        val uptime = String.format(
            Locale.US,
            "%.2f %.2f\n",
            uptimeSeconds,
            aggregateIdleSeconds,
        )

        val mem = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val freePages = mem.availMem / pageSize
        val totalPages = mem.totalMem / pageSize
        val usedPages = (totalPages - freePages).coerceAtLeast(0L)

        val vmstat = buildString {
            appendLine("nr_free_pages $freePages")
            appendLine("nr_inactive_anon 0")
            appendLine("nr_active_anon $usedPages")
            appendLine("nr_inactive_file 0")
            appendLine("nr_active_file 0")
            appendLine("nr_unevictable 0")
            appendLine("nr_mlock 0")
            appendLine("nr_page_table_pages 0")
            appendLine("nr_kernel_stack 0")
            appendLine("nr_anon_pages $usedPages")
            appendLine("nr_mapped 0")
            appendLine("nr_file_pages 0")
            appendLine("nr_dirty 0")
            appendLine("nr_writeback 0")
            appendLine("nr_shmem 0")
            appendLine("pgpgin 0")
            appendLine("pgpgout 0")
            appendLine("pswpin 0")
            appendLine("pswpout 0")
            appendLine("pgfault 0")
            appendLine("pgmajfault 0")
        }

        val uname = runCatching { Os.uname() }.getOrNull()
        val release = uname?.release ?: System.getProperty("os.version") ?: "android"
        val version = "Linux version $release (Proroot Android host compatibility bridge)\n"
        val loadavg = "0.00 0.00 0.00 1/1 ${Process.myPid()}\n"

        writeAtomic(paths.procStat, stat)
        writeAtomic(paths.procUptime, uptime)
        writeAtomic(paths.procLoadavg, loadavg)
        writeAtomic(paths.procVersion, version)
        writeAtomic(paths.procVmstat, vmstat)

        val hostInfo = buildString {
            appendLine("model=${Build.MODEL}")
            appendLine("device=${Build.DEVICE}")
            appendLine("hardware=${Build.HARDWARE}")
            appendLine("board=${Build.BOARD}")
            if (Build.VERSION.SDK_INT >= 31) {
                appendLine("soc_model=${Build.SOC_MODEL}")
                appendLine("soc_manufacturer=${Build.SOC_MANUFACTURER}")
            }
            appendLine("android_sdk=${Build.VERSION.SDK_INT}")
            appendLine("cpu_count=$cpuCount")
            appendLine("memory_total_bytes=${mem.totalMem}")
            appendLine("memory_available_bytes=${mem.availMem}")
        }
        writeAtomic(paths.hostInfoFile, hostInfo)
    }

    private fun visibleProcessCpuTicks(): Long {
        val proc = File("/proc")
        val entries = proc.listFiles() ?: return 0L
        var total = 0L

        for (entry in entries) {
            if (!entry.name.all(Char::isDigit)) continue
            val line = runCatching { File(entry, "stat").readText() }.getOrNull() ?: continue
            val commEnd = line.lastIndexOf(')')
            if (commEnd < 0 || commEnd + 2 >= line.length) continue

            val fields = line.substring(commEnd + 2)
                .trim()
                .split(Regex("\\s+"))
            if (fields.size <= 12) continue

            val user = fields[11].toLongOrNull() ?: continue
            val system = fields[12].toLongOrNull() ?: continue
            total += user + system
        }
        return total
    }

    private fun writeAtomic(target: File, content: String) {
        target.parentFile?.mkdirs()
        val staging = File(target.parentFile, target.name + ".new")
        staging.writeText(content)
        if (!staging.renameTo(target)) {
            target.writeText(content)
            staging.delete()
        }
    }
}
