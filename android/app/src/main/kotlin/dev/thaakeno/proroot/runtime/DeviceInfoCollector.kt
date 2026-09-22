package dev.thaakeno.proroot.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.system.Os
import java.io.File
import java.util.Locale

class DeviceInfoCollector(
    context: Context,
    private val paths: RuntimePaths,
) {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)

    fun collect(): Map<String, Any?> {
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val storage = StatFs(appContext.filesDir.absolutePath)
        val host = readHostInfo()
        val vulkan = File(paths.rootfs, "tmp/proroot-vulkan-summary.txt")
            .takeIf { it.isFile }
            ?.let { runCatching(it::readText).getOrNull() }
            .orEmpty()
        val kgslAvailable = File("/dev/kgsl-3d0").exists()
        val desktopLog = File(paths.logsDir, "desktop-session.log")
            .takeIf { it.isFile }
            ?.let { runCatching(it::readText).getOrNull() }
            .orEmpty()
        val plasmaSoftwareRenderer = desktopLog.contains(
            "forcing Qt Quick to use the software renderer",
            ignoreCase = true,
        )

        val gpuName = summaryValue(vulkan, "deviceName")
            ?: if (kgslAvailable) "Qualcomm Adreno via KGSL" else "Not detected"
        val gpuDriver = summaryValue(vulkan, "driverName")
            ?: if (kgslAvailable) "Mesa Freedreno / Turnip" else "Not detected"
        val gpuApi = summaryValue(vulkan, "apiVersion")
        val maxCpuKhz = cpuMaxFrequencyKhz()

        return linkedMapOf(
            "Device" to linkedMapOf(
                "Manufacturer" to Build.MANUFACTURER,
                "Brand" to Build.BRAND,
                "Model" to Build.MODEL,
                "Device" to Build.DEVICE,
                "Board" to Build.BOARD,
                "Hardware" to Build.HARDWARE,
            ),
            "CPU" to linkedMapOf(
                "SoC" to host["soc_model"].orEmpty().ifBlank {
                    if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
                },
                "SoC manufacturer" to host["soc_manufacturer"].orEmpty().ifBlank {
                    if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else Build.MANUFACTURER
                },
                "Architecture" to Build.SUPPORTED_ABIS.joinToString(", "),
                "Logical cores" to Runtime.getRuntime().availableProcessors(),
                "Maximum frequency" to maxCpuKhz?.let {
                    String.format(Locale.US, "%.2f GHz", it / 1_000_000.0)
                },
            ).filterValues { it != null && it.toString().isNotBlank() },
            "GPU" to linkedMapOf(
                "Renderer" to gpuName,
                "Driver" to gpuDriver,
                "Vulkan API" to gpuApi,
                "Android device" to if (kgslAvailable) "/dev/kgsl-3d0" else "Unavailable",
                "Application GPU path" to if (kgslAvailable) {
                    "Hardware accelerated KGSL · Freedreno/Turnip"
                } else {
                    "KGSL unavailable"
                },
                "Plasma Qt Quick" to if (plasmaSoftwareRenderer) {
                    "Software renderer · Anland PRoot has no DRM render node"
                } else if (kgslAvailable) {
                    "No software fallback recorded in the current session"
                } else {
                    "Software / unverified"
                },
                "DRM render node" to "Unavailable in PRoot · Anland uses surfaceless EGL",
            ).filterValues { it != null && it.toString().isNotBlank() },
            "Memory" to linkedMapOf(
                "Total RAM" to formatBytes(memory.totalMem),
                "Available RAM" to formatBytes(memory.availMem),
                "Low-memory state" to memory.lowMemory,
            ),
            "Storage" to linkedMapOf(
                "Total internal" to formatBytes(storage.totalBytes),
                "Available internal" to formatBytes(storage.availableBytes),
            ),
            "System" to linkedMapOf(
                "Android" to Build.VERSION.RELEASE,
                "SDK" to Build.VERSION.SDK_INT,
                "Kernel" to (runCatching { Os.uname().release }.getOrNull() ?: "Unknown"),
                "Linux runtime" to "ProRoot",
                "Display transport" to "Anland / Wayland",
            ),
        )
    }

    private fun readHostInfo(): Map<String, String> =
        paths.hostInfoFile
            .takeIf { it.isFile }
            ?.readLines()
            ?.mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null
                else line.substring(0, separator) to line.substring(separator + 1)
            }
            ?.toMap()
            .orEmpty()

    private fun summaryValue(text: String, key: String): String? {
        if (text.isBlank()) return null
        return text.lineSequence()
            .map(String::trim)
            .firstNotNullOfOrNull { line ->
                when {
                    line.startsWith("$key =") -> line.substringAfter('=').trim()
                    line.startsWith("$key:") -> line.substringAfter(':').trim()
                    else -> null
                }
            }
            ?.takeIf(String::isNotBlank)
    }

    private fun cpuMaxFrequencyKhz(): Long? {
        val cpuRoot = File("/sys/devices/system/cpu")
        return cpuRoot.listFiles()
            ?.asSequence()
            ?.filter { it.name.matches(Regex("cpu\\d+")) }
            ?.mapNotNull { cpu ->
                listOf(
                    File(cpu, "cpufreq/cpuinfo_max_freq"),
                    File(cpu, "cpufreq/scaling_max_freq"),
                ).firstNotNullOfOrNull { file ->
                    runCatching { file.readText().trim().toLong() }.getOrNull()
                }
            }
            ?.maxOrNull()
    }

    private fun formatBytes(bytes: Long): String {
        val gib = bytes / 1024.0 / 1024.0 / 1024.0
        return String.format(Locale.US, "%.2f GiB", gib)
    }
}
