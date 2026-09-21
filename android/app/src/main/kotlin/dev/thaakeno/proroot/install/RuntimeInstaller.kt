package dev.thaakeno.proroot.install

import android.content.Context
import android.system.Os
import dev.thaakeno.proroot.runtime.ProrootRunner
import dev.thaakeno.proroot.runtime.RuntimePaths
import dev.thaakeno.proroot.runtime.RuntimePhase
import dev.thaakeno.proroot.runtime.RuntimeStatus
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class RuntimeInstaller(
    private val context: Context,
    private val paths: RuntimePaths,
    runner: ProrootRunner,
) {
    private val extractor = SafeArchiveExtractor()
    private val downloads = DownloadCoordinator(paths.cacheDir)
    private val provisioner = DesktopProvisioner(runner, android.os.Process.myUid())

    suspend fun install(onStatus: (RuntimeStatus) -> Unit) {
        paths.ensureHostDirectories()
        val assets = downloads.downloadAll(AssetCatalog.all, onStatus)
        val staging = paths.rootfsStaging
        val packageDir = File(staging, "opt/proroot-packages")

        onStatus(RuntimeStatus(RuntimePhase.extracting, 0.05, "Extracting Debian"))
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not create staging rootfs" }
        extractor.extractTarXz(requireAsset(assets, RuntimeAssetKind.ROOTFS), staging)
        installGuestScripts(staging)

        packageDir.mkdirs()
        stagePackage(requireAsset(assets, RuntimeAssetKind.ANLAND_GUEST), File(packageDir, "anland/anland.deb"))
        stagePackage(requireAsset(assets, RuntimeAssetKind.XWAYLAND_PACKAGE), File(packageDir, "xwayland/xwayland.deb"))
        extractor.extractZip(requireAsset(assets, RuntimeAssetKind.KWIN_PACKAGES), File(packageDir, "kwin"))

        onStatus(RuntimeStatus(RuntimePhase.extracting, 0.40, "Installing Brave"))
        installBrave(staging, requireAsset(assets, RuntimeAssetKind.BRAVE))

        onStatus(RuntimeStatus(RuntimePhase.provisioning, 0.55, "Installing KDE Plasma and applications"))
        provisioner.provisionBase(staging)

        onStatus(RuntimeStatus(RuntimePhase.provisioning, 0.88, "Installing pinned Adreno 840 graphics"))
        extractor.extractTarGz(requireAsset(assets, RuntimeAssetKind.MESA_OVERLAY), staging)
        provisioner.finalizeGraphicsAndVerify(staging)

        onStatus(RuntimeStatus(RuntimePhase.provisioning, 0.98, "Activating verified Linux environment"))
        activate(staging)
        paths.installMarker.writeText(
            "runtime=1\ndevice=${android.os.Build.DEVICE}\nuid=${android.os.Process.myUid()}\nabi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}\n"
        )
    }

    fun canRollback(): Boolean = paths.rootfsPrevious.isDirectory

    fun rollback(): Boolean {
        if (!canRollback()) return false

        paths.rootfsStaging.deleteRecursively()
        paths.rootfs.deleteRecursively()
        check(paths.rootfsPrevious.renameTo(paths.rootfs)) {
            "Could not restore previous rootfs"
        }

        if (paths.previousInstallMarker.isFile) {
            paths.previousInstallMarker.copyTo(paths.installMarker, overwrite = true)
            paths.previousInstallMarker.delete()
        } else {
            paths.installMarker.writeText("runtime=recovered\n")
        }
        return true
    }

    private fun installGuestScripts(rootfs: File) {
        val targetDir = File(rootfs, "usr/local/lib/proroot")
        targetDir.mkdirs()
        val scripts = context.assets.list("guest")?.toList().orEmpty()
        check(scripts.isNotEmpty()) { "Guest runtime scripts are missing from APK assets" }

        scripts.forEach { name ->
            val target = File(targetDir, name)
            context.assets.open("guest/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Os.chmod(target.absolutePath, 0x1ED)
        }
    }

    private fun installBrave(rootfs: File, archive: File) {
        val braveDir = File(rootfs, "opt/brave")
        braveDir.deleteRecursively()
        extractor.extractZip(archive, braveDir)

        val binary = braveDir.walkTopDown()
            .firstOrNull { file -> file.isFile && (file.name == "brave" || file.name == "brave-browser") }
            ?: error("Brave archive does not contain the browser executable")

        braveDir.walkTopDown()
            .filter { file ->
                file.isFile && file.name in setOf("brave", "brave-browser", "chrome-sandbox", "chrome_crashpad_handler")
            }
            .forEach { file -> runCatching { Os.chmod(file.absolutePath, 0x1ED) } }

        val link = File(rootfs, "usr/local/bin/brave-browser")
        link.parentFile?.mkdirs()
        link.delete()
        val guestBinary = binary.absolutePath.removePrefix(rootfs.absolutePath)
        Files.createSymbolicLink(link.toPath(), Path.of(guestBinary))

        File(rootfs, "usr/share/applications/brave-browser.desktop").apply {
            parentFile?.mkdirs()
            writeText("""
                [Desktop Entry]
                Type=Application
                Name=Brave Browser
                GenericName=Web Browser
                Exec=/usr/local/bin/brave-browser %U
                Terminal=false
                Categories=Network;WebBrowser;
                MimeType=text/html;x-scheme-handler/http;x-scheme-handler/https;
                StartupNotify=true
            """.trimIndent() + "\n")
        }
    }

    private fun stagePackage(source: File, target: File) {
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
    }

    private fun activate(staging: File) {
        check(staging.isDirectory) { "Staging rootfs disappeared" }

        paths.rootfsPrevious.deleteRecursively()
        paths.previousInstallMarker.delete()

        if (paths.rootfs.exists()) {
            check(paths.rootfs.renameTo(paths.rootfsPrevious)) {
                "Could not preserve previous rootfs"
            }
            if (paths.installMarker.isFile) {
                paths.installMarker.copyTo(paths.previousInstallMarker, overwrite = true)
            }
        }

        try {
            check(staging.renameTo(paths.rootfs)) { "Could not activate staged rootfs" }
        } catch (t: Throwable) {
            paths.rootfs.deleteRecursively()
            if (paths.rootfsPrevious.exists()) {
                paths.rootfsPrevious.renameTo(paths.rootfs)
            }
            if (paths.previousInstallMarker.isFile) {
                paths.previousInstallMarker.copyTo(paths.installMarker, overwrite = true)
                paths.previousInstallMarker.delete()
            }
            throw t
        }
    }

    private fun requireAsset(assets: Map<RuntimeAssetKind, File>, kind: RuntimeAssetKind): File =
        assets[kind] ?: error("Missing runtime asset: $kind")
}
