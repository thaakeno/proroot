package dev.thaakeno.proroot.install

import android.content.Context
import android.os.StatFs
import android.system.Os
import dev.thaakeno.proroot.runtime.GuestRunner
import dev.thaakeno.proroot.runtime.RuntimePaths
import dev.thaakeno.proroot.runtime.RuntimePhase
import dev.thaakeno.proroot.runtime.RuntimeStatus
import java.io.File

class RuntimeInstaller(
    private val context: Context,
    private val paths: RuntimePaths,
    installRunner: GuestRunner,
) {
    companion object {
        private const val INTERNAL_READY_MARKER = ".proroot-runtime-ready"
    }
    private val extractor = SafeArchiveExtractor()
    private val downloads = DownloadCoordinator(paths.cacheDir)
    private val journal = InstallJournal(paths)
    private val provisioner = DesktopProvisioner(
        runner = installRunner,
        desktopUid = android.os.Process.myUid(),
        journal = journal,
    )

    suspend fun install(onStatus: (RuntimeStatus) -> Unit) {
        paths.ensureHostDirectories()
        recoverInterruptedActivation()
        journal.begin()

        fun emit(status: RuntimeStatus) {
            journal.status(status)
            onStatus(status)
        }

        try {
            ensureFreeSpace()
            val totalDownloadBytes = AssetCatalog.all.sumOf { it.size }
            val assets = downloads.downloadAll(AssetCatalog.all) { status ->
                emit(status.copy(progress = status.progress * 0.40))
            }
        val staging = paths.rootfsStaging
        val packageDir = File(staging, "opt/proroot-packages")

        emit(
            installStatus(
                phase = RuntimePhase.extracting,
                progress = 0.42,
                message = "Extracting Debian",
                downloadedBytes = totalDownloadBytes,
            ),
        )
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not create staging rootfs" }
        extractor.extractTarXz(
            requireAsset(assets, RuntimeAssetKind.ROOTFS),
            staging,
            stripComponents = 1,
        )
        verifyRootfsLayout(staging)
        installGuestScripts(staging)

        emit(
            installStatus(
                phase = RuntimePhase.extracting,
                progress = 0.44,
                message = "Staging verified desktop packages",
                downloadedBytes = totalDownloadBytes,
            ),
        )
        packageDir.mkdirs()
        stagePackage(requireAsset(assets, RuntimeAssetKind.ANLAND_GUEST), File(packageDir, "anland/anland.deb"))
        stagePackage(requireAsset(assets, RuntimeAssetKind.XWAYLAND_PACKAGE), File(packageDir, "xwayland/xwayland.deb"))
        stagePackage(requireAsset(assets, RuntimeAssetKind.BRAVE), File(packageDir, "brave/brave.deb"))
        extractor.extractZip(requireAsset(assets, RuntimeAssetKind.KWIN_PACKAGES), File(packageDir, "kwin"))

        provisioner.provisionBase(staging) { stage ->
            emit(
                installStatus(
                    phase = RuntimePhase.provisioning,
                    progress = stage.progress,
                    message = stage.message,
                    downloadedBytes = totalDownloadBytes,
                ),
            )
        }

        emit(
            installStatus(
                phase = RuntimePhase.provisioning,
                progress = 0.89,
                message = "Installing pinned Adreno 840 graphics",
                downloadedBytes = totalDownloadBytes,
            ),
        )
        extractor.extractTarGz(requireAsset(assets, RuntimeAssetKind.MESA_OVERLAY), staging)

        emit(
            installStatus(
                phase = RuntimePhase.provisioning,
                progress = 0.94,
                message = "Verifying GPU and desktop compatibility",
                downloadedBytes = totalDownloadBytes,
            ),
        )
        provisioner.finalizeGraphicsAndVerify(staging)
        File(staging, INTERNAL_READY_MARKER).writeText(markerContents("verified"))

        emit(
            installStatus(
                phase = RuntimePhase.provisioning,
                progress = 0.98,
                message = "Activating verified Linux environment",
                downloadedBytes = totalDownloadBytes,
            ),
        )
        activate(staging)
        paths.installMarker.writeText(markerContents("active"))
        journal.success()
        } catch (t: Throwable) {
            journal.failure(t)
            throw t
        }
    }

    private fun verifyRootfsLayout(rootfs: File) {
        check(File(rootfs, "etc").isDirectory) {
            "Debian archive extracted without /etc; rootfs layout is invalid"
        }
        check(File(rootfs, "usr").isDirectory) {
            "Debian archive extracted without /usr; rootfs layout is invalid"
        }
        check(
            File(rootfs, "bin/bash").exists() ||
                File(rootfs, "usr/bin/bash").exists(),
        ) {
            "Debian archive extracted without bash; rootfs layout is invalid"
        }
    }

    private fun ensureFreeSpace() {
        val required = 7L * 1024L * 1024L * 1024L
        val available = StatFs(paths.machineDir.absolutePath).availableBytes
        check(available >= required) {
            val availableGiB = available.toDouble() / 1024.0 / 1024.0 / 1024.0
            "At least 7 GB of free internal storage is required; " +
                "%.1f GB is currently available".format(availableGiB)
        }
    }

    fun recoverInterruptedActivation() {
        paths.ensureHostDirectories()

        if (!paths.rootfs.exists() && paths.rootfsPrevious.isDirectory) {
            check(paths.rootfsPrevious.renameTo(paths.rootfs)) {
                "Could not recover previous rootfs after interrupted activation"
            }
            if (paths.previousInstallMarker.isFile) {
                paths.previousInstallMarker.copyTo(paths.installMarker, overwrite = true)
                paths.previousInstallMarker.delete()
            }
        }

        val internalMarker = File(paths.rootfs, INTERNAL_READY_MARKER)
        if (paths.rootfs.isDirectory && !paths.installMarker.isFile && internalMarker.isFile) {
            paths.installMarker.writeText(markerContents("recovered"))
        }
    }

    fun lastFailure(): String? = journal.lastFailure()

    fun wasInterrupted(): Boolean = journal.wasInterrupted()

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
        } else if (File(paths.rootfs, INTERNAL_READY_MARKER).isFile) {
            paths.installMarker.writeText(markerContents("rollback"))
        } else {
            error("Previous rootfs has no verified runtime marker")
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

            val executable = name.startsWith("start-") ||
                name.startsWith("launch-") ||
                name.startsWith("set-") ||
                name.endsWith("-bridge.py")
            Os.chmod(
                target.absolutePath,
                if (executable) 0x1ED else 0x1A4,
            )
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

    private fun installStatus(
        phase: RuntimePhase,
        progress: Double,
        message: String,
        downloadedBytes: Long,
    ): RuntimeStatus = RuntimeStatus(
        phase = phase,
        progress = progress,
        message = message,
        downloadedBytes = downloadedBytes,
        totalBytes = downloadedBytes,
        speedBytesPerSecond = 0,
    )

    private fun markerContents(state: String): String =
        buildString {
            appendLine("runtime=1")
            appendLine("state=$state")
            appendLine("device=${android.os.Build.DEVICE}")
            appendLine("uid=${android.os.Process.myUid()}")
            appendLine("abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
        }

    private fun requireAsset(assets: Map<RuntimeAssetKind, File>, kind: RuntimeAssetKind): File =
        assets[kind] ?: error("Missing runtime asset: $kind")
}
