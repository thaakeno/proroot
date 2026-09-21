package dev.thaakeno.proroot.runtime

import java.io.File

data class DesktopApp(
    val id: String,
    val name: String,
    val genericName: String?,
    val icon: String?,
    val iconPath: String?,
    val categories: List<String>,
) {
    fun asMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "genericName" to genericName,
        "icon" to icon,
        "iconPath" to iconPath,
        "categories" to categories,
    )
}

class DesktopAppCatalog(private val paths: RuntimePaths) {
    @Volatile private var iconIndexStamp: Long = Long.MIN_VALUE
    @Volatile private var iconIndex: Map<String, File> = emptyMap()

    fun list(): List<DesktopApp> {
        if (!paths.rootfs.isDirectory) return emptyList()

        val icons = icons()
        val roots = listOf(
            File(paths.rootfs, "usr/share/applications"),
            File(paths.rootfs, "usr/local/share/applications"),
            File(paths.rootfs, "home/linux/.local/share/applications"),
        )

        val seen = linkedMapOf<String, DesktopApp>()
        roots.filter(File::isDirectory).forEach { root ->
            root.listFiles { file -> file.isFile && file.extension == "desktop" }
                ?.sortedBy(File::name)
                ?.forEach { file ->
                    parse(file, icons)?.let { seen[it.id] = it }
                }
        }

        val priority = mapOf(
            "brave-browser.desktop" to 0,
            "firefox-esr.desktop" to 1,
            "org.kde.konsole.desktop" to 2,
            "code.desktop" to 3,
            "org.kde.dolphin.desktop" to 4,
            "org.kde.kate.desktop" to 5,
        )

        return seen.values.sortedWith(
            compareBy<DesktopApp> { priority[it.id] ?: 100 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
    }

    private fun parse(file: File, icons: Map<String, File>): DesktopApp? {
        var inDesktopEntry = false
        val values = linkedMapOf<String, String>()

        file.useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.startsWith("[") && line.endsWith("]")) {
                    inDesktopEntry = line == "[Desktop Entry]"
                    return@forEach
                }
                if (!inDesktopEntry || line.isBlank() || line.startsWith("#")) return@forEach
                val separator = line.indexOf('=')
                if (separator <= 0) return@forEach
                val key = line.substring(0, separator)
                if (key !in values) values[key] = line.substring(separator + 1)
            }
        }

        if (values["Type"] != "Application") return null
        if (values["Hidden"].equals("true", true) || values["NoDisplay"].equals("true", true)) return null

        val onlyShowIn = values["OnlyShowIn"].orEmpty().split(';').filter(String::isNotBlank)
        if (onlyShowIn.isNotEmpty() && onlyShowIn.none { it.equals("KDE", true) }) return null

        val notShowIn = values["NotShowIn"].orEmpty().split(';').filter(String::isNotBlank)
        if (notShowIn.any { it.equals("KDE", true) }) return null

        val name = values["Name"]?.takeIf(String::isNotBlank) ?: return null
        val icon = values["Icon"]?.takeIf(String::isNotBlank)
        val categories = values["Categories"].orEmpty().split(';').filter(String::isNotBlank)

        return DesktopApp(
            id = file.name,
            name = name,
            genericName = values["GenericName"]?.takeIf(String::isNotBlank),
            icon = icon,
            iconPath = resolveIcon(icon, icons)?.absolutePath,
            categories = categories,
        )
    }

    private fun resolveIcon(icon: String?, icons: Map<String, File>): File? {
        if (icon.isNullOrBlank()) return null

        if (icon.startsWith('/')) {
            return File(paths.rootfs, icon.removePrefix("/")).takeIf(File::isFile)
        }

        val exact = icons[icon]
        if (exact != null) return exact

        val key = icon.substringAfterLast('/').substringBeforeLast('.')
        return icons[key]
    }

    @Synchronized
    private fun icons(): Map<String, File> {
        val stamp = paths.rootfs.lastModified()
        if (stamp == iconIndexStamp && iconIndex.isNotEmpty()) return iconIndex

        val result = linkedMapOf<String, File>()
        val roots = listOf(
            File(paths.rootfs, "usr/share/icons/hicolor"),
            File(paths.rootfs, "usr/share/icons/breeze"),
            File(paths.rootfs, "usr/share/icons/breeze-dark"),
            File(paths.rootfs, "usr/share/pixmaps"),
        )

        roots.filter(File::isDirectory).forEach { root ->
            root.walkTopDown()
                .maxDepth(7)
                .filter { file ->
                    file.isFile && file.extension.lowercase() in setOf("svg", "png", "webp")
                }
                .forEach { file ->
                    val key = file.nameWithoutExtension
                    val current = result[key]
                    if (current == null || iconScore(file) > iconScore(current)) {
                        result[key] = file
                    }
                    result.putIfAbsent(file.name, file)
                }
        }

        iconIndexStamp = stamp
        iconIndex = result
        return result
    }

    private fun iconScore(file: File): Int {
        val extensionScore = when (file.extension.lowercase()) {
            "svg" -> 10_000
            "png" -> 8_000
            "webp" -> 7_000
            else -> 0
        }
        val sizeScore = Regex("(\\d+)x(\\d+)")
            .find(file.absolutePath)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.coerceAtMost(1024)
            ?: 0
        return extensionScore + sizeScore
    }
}
