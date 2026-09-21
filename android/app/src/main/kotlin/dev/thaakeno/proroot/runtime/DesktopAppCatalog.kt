package dev.thaakeno.proroot.runtime

import java.io.File

data class DesktopApp(
    val id: String,
    val name: String,
    val genericName: String?,
    val icon: String?,
    val categories: List<String>,
) {
    fun asMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "genericName" to genericName,
        "icon" to icon,
        "categories" to categories,
    )
}

class DesktopAppCatalog(private val paths: RuntimePaths) {
    fun list(): List<DesktopApp> {
        if (!paths.rootfs.isDirectory) return emptyList()

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
                    parse(file)?.let { seen[it.id] = it }
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

    private fun parse(file: File): DesktopApp? {
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

        val onlyShowIn = values["OnlyShowIn"].orEmpty()
            .split(';')
            .filter(String::isNotBlank)
        if (onlyShowIn.isNotEmpty() && onlyShowIn.none { it.equals("KDE", true) }) return null

        val notShowIn = values["NotShowIn"].orEmpty()
            .split(';')
            .filter(String::isNotBlank)
        if (notShowIn.any { it.equals("KDE", true) }) return null

        val name = values["Name"]?.takeIf(String::isNotBlank) ?: return null
        val categories = values["Categories"].orEmpty()
            .split(';')
            .filter(String::isNotBlank)

        return DesktopApp(
            id = file.name,
            name = name,
            genericName = values["GenericName"]?.takeIf(String::isNotBlank),
            icon = values["Icon"]?.takeIf(String::isNotBlank),
            categories = categories,
        )
    }
}
