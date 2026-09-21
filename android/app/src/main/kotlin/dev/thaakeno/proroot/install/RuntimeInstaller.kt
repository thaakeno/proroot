package dev.thaakeno.proroot.install

import dev.thaakeno.proroot.runtime.ProrootRunner
import dev.thaakeno.proroot.runtime.RuntimePaths
import dev.thaakeno.proroot.runtime.RuntimePhase
import dev.thaakeno.proroot.runtime.RuntimeStatus
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class RuntimeInstaller(
    private val paths: RuntimePaths,
    runner: ProrootRunner,
) {
    private val extractor = SafeArchiveExtractor()
    private val downloads = DownloadCoordinator(paths.cacheDir)
    private val provisioner = DesktopProvisioner(runner)

    suspend fun install(onStatus: (RuntimeStatus) -> Unit) {
        paths.ensureHostDirectories()
        val assets = downloads.downloadAll(AssetCatalog.all, onStatus)
        val staging = paths.rootfsStaging
        val packageDir = File(staging, "opt/proroot-packages")

        onStatus(RuntimeStatus(RuntimePhase.extracting, 0.05, "Extracting Debian"))
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not create staging rootfs" }
        extractor.extractTarXz(requireAsset(assets, RuntimeAssetKind.ROOTFS), staging)

        onStatus(RuntimeStatus(RuntimePhase.extracting, 0.35, "Installing Adreno 840 graphics"))
        extractor.extractTarGz(requireAsset(assets, RuntimeAssetKind.MESA_OVERLAY), staging)

        packageDir.mkdirs()
        stagePackage(
            requireAsset(assets, RuntimeAssetKind.ANLAND_GUEST),
            File(packageDir, "anland/anland.deb"),
        )
        stagePackage(
            requireAsset(assets, RuntimeAssetKind.XWAYLAND_PACKAGE),
            File(packageDir, "xwayland/xwayland.deb"),
        )
        extractor.extractZip(
            requireAsset(assets, RuntimeAssetKind.KWIN_PACKAGES),
            File(packageDir, "kwin"),
        )

        onStatus(RuntimeStatus(RuntimePhase.extracting, 0.62, "Installing Brave"))
        installBrave(staging, requireAsset(assets, RuntimeAssetKind.BRAVE))

        onStatus(RuntimeStatus(RuntimePhase.provisioning, 0.70, "Installing KDE Plasma and applications"))
        provisioner.provision(staging)

        onStatus(RuntimeStatus(RuntimePhase.provisioning, 0.97, "Finalizing Linux environment"))
        activate(staging)

        paths.installMarker.writeText(
            "runtime=1\ndevice=${android.os.Build.DEVICE}\nabi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}\n",
        )
    }

    private fun installBrave(rootfs: File, archive: File) {
        val braveDir = File(rootfs, "opt/brave")
        braveDir.deleteRecursively()
        extractor.extractZip(archive, braveDir)

        val binary = braveDir.walkTopDown()
            .firstOrNull { file -> file.isFile && (file.name == "brave" || file.name == "brave-browser") }
            ?: error("Brave archive does not contain the browser executable")

        val link = File(rootfs, "usr/local/bin/brave-browser")
        link.parentFile?.mkdirs()
        link.delete()
        val guestBinary = binary.absolutePath.removePrefix(rootfs.absolutePath)
        Files.createSymbolicLink(link.toPath(), Path.of(guestBinary))

        val desktop = File(rootfs, "usr/share/applications/brave-browser.desktop")
        desktop.parentFile?.mkdirs()
        desktop.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=Brave Browser
            GenericName=Web Browser
            Exec=/usr/local/bin/brave-browser %U
            Terminal=false
            Categories=Network;WebBrowser;
            MimeType=text/html;x-scheme-handler/http;x-scheme-handler/https;
            StartupNotify=true
            """.trimIndent() + "\n",
        )
    }

    private fun stagePackage(source: File, target: File) {
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
    }

    private fun activate(staging: File) {
        check(staging.isDirectory) { "Staging rootfs disappeared" }
        paths.rootfsPrevious.deleteRecursively()

        if (paths.rootfs.exists()) {
            check(paths.rootfs.renameTo(paths.rootfsPrevious)) {
                "Could not preserve previous rootfs"
            }
        }

        try {
            check(staging.renameTo(paths.rootfs)) { "Could not activate staged rootfs" }
            paths.rootfsPrevious.deleteRecursively()
        } catch (t: Throwable) {
            paths.rootfs.deleteRecursively()
            if (paths.rootfsPrevious.exists()) paths.rootfsPrevious.renameTo(paths.rootfs)
            throw t
        }
    }

    private fun requireAsset(
        assets: Map<RuntimeAssetKind, File>,
        kind: RuntimeAssetKind,
    ): File = assets[kind] ?: error("Missing runtime asset: $kind")
}
