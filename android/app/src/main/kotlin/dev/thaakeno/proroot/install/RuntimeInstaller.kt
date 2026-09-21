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
    private val installRunner: GuestRunner,
) {
    companion object {
        private const val INTERNAL_READY_MARKER = ".proroot-runtime-ready"
        private const val STAGING_RESUME_MARKER = ".proroot-staging-resumable"
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
        val hadPreviousFailure = paths.lastInstallFailure.isFile
        journal.begin()
        val installStartedAtMs = System.currentTimeMillis()

        fun emit(status: RuntimeStatus) {
            val elapsedSeconds = ((System.currentTimeMillis() - installStartedAtMs) / 1000L)
                .coerceAtLeast(0L)
            val fullEta = status.etaSeconds ?: when (status.phase) {
                RuntimePhase.downloading -> {
                    if (status.speedBytesPerSecond > 0 &&
                        status.totalBytes > status.downloadedBytes
                    ) {
                        ((status.totalBytes - status.downloadedBytes) /
                            status.speedBytesPerSecond) + 900L
                    } else {
                        900L
                    }
                }
                RuntimePhase.extracting -> 900L
                RuntimePhase.provisioning -> ((1.0 - status.progress)
                    .coerceAtLeast(0.0) * 1_500.0).toLong()
                else -> null
            }

            val enriched = status.copy(
                elapsedSeconds = elapsedSeconds,
                etaSeconds = fullEta,
            )
            journal.status(enriched)
            onStatus(enriched)
        }

        try {
            ensureFreeSpace()
            val totalDownloadBytes = AssetCatalog.all.sumOf { it.size }
            val assets = downloads.downloadAll(AssetCatalog.all) { status ->
                emit(status.copy(progress = status.progress * 0.40))
            }
        val staging = paths.rootfsStaging
        val packageDir = File(staging, "opt/proroot-packages")

        val rootfsAsset = requireAsset(assets, RuntimeAssetKind.ROOTFS)
        val resumeStaging = canResumeStaging(
            staging = staging,
            rootfsAsset = rootfsAsset,
            allowLegacyFailedStaging = hadPreviousFailure,
        )

        if (resumeStaging) {
            emit(
                installStatus(
                    phase = RuntimePhase.extracting,
                    progress = 0.44,
                    message = "Resuming previous setup",
                    downloadedBytes = totalDownloadBytes,
                    etaSeconds = 180,
                ),
            )
            verifyRootfsLayout(staging)
            installGuestScripts(staging)
            adoptStagingAptCache(staging)
            repairLegacyFailedPackages(staging)
        } else {
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
                rootfsAsset,
                staging,
                stripComponents = 1,
            )
            verifyRootfsLayout(staging)
            installGuestScripts(staging)
        }
        writeStagingMarker(staging, rootfsAsset)

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
        extractor.extractZip(requireAsset(assets, RuntimeAssetKind.KWIN_PACKAGES), File(packageDir, "kwin"))

        provisioner.provisionBase(staging) { stage ->
            emit(
                installStatus(
                    phase = RuntimePhase.provisioning,
                    progress = stage.progress,
                    message = stage.message,
                    downloadedBytes = if (stage.stageTotalBytes > 0L) {
                        stage.stageDownloadedBytes
                    } else {
                        totalDownloadBytes
                    },
                    totalBytes = if (stage.stageTotalBytes > 0L) {
                        stage.stageTotalBytes
                    } else {
                        totalDownloadBytes
                    },
                    speedBytesPerSecond = stage.stageSpeedBytesPerSecond,
                    etaSeconds = stage.etaSeconds,
                    stageProgress = stage.stageProgress,
                    stageDetail = stage.stageDetail,
                    stageDownloadedBytes = stage.stageDownloadedBytes,
                    stageTotalBytes = stage.stageTotalBytes,
                    stageSpeedBytesPerSecond = stage.stageSpeedBytesPerSecond,
                    completedItems = stage.completedItems,
                    totalItems = stage.totalItems,
                ),
            )
        }

        emit(
            installStatus(
                phase = RuntimePhase.provisioning,
                progress = 0.94,
                message = "Installing pinned Adreno 840 graphics",
                etaSeconds = 45,
                downloadedBytes = totalDownloadBytes,
            ),
        )
        extractor.extractTarGz(requireAsset(assets, RuntimeAssetKind.MESA_OVERLAY), staging)

        emit(
            installStatus(
                phase = RuntimePhase.provisioning,
                progress = 0.97,
                message = "Verifying GPU and desktop compatibility",
                etaSeconds = 30,
                downloadedBytes = totalDownloadBytes,
            ),
        )
        provisioner.finalizeGraphicsAndVerify(staging)
        File(staging, INTERNAL_READY_MARKER).writeText(markerContents("verified"))

        emit(
            installStatus(
                phase = RuntimePhase.provisioning,
                progress = 0.99,
                message = "Activating verified Linux environment",
                etaSeconds = 5,
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
        totalBytes: Long = downloadedBytes,
        speedBytesPerSecond: Long = 0,
        etaSeconds: Long? = null,
        stageProgress: Double? = null,
        stageDetail: String? = null,
        stageDownloadedBytes: Long = 0,
        stageTotalBytes: Long = 0,
        stageSpeedBytesPerSecond: Long = 0,
        completedItems: Int = 0,
        totalItems: Int = 0,
    ): RuntimeStatus = RuntimeStatus(
        phase = phase,
        progress = progress,
        message = message,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        speedBytesPerSecond = speedBytesPerSecond,
        etaSeconds = etaSeconds,
        stageProgress = stageProgress,
        stageDetail = stageDetail,
        stageDownloadedBytes = stageDownloadedBytes,
        stageTotalBytes = stageTotalBytes,
        stageSpeedBytesPerSecond = stageSpeedBytesPerSecond,
        completedItems = completedItems,
        totalItems = totalItems,
    )

    private fun canResumeStaging(
        staging: File,
        rootfsAsset: RuntimeAsset,
        allowLegacyFailedStaging: Boolean,
    ): Boolean {
        if (!staging.isDirectory) return false
        if (!File(staging, "etc/debian_version").isFile) return false
        if (!File(staging, "var/lib/dpkg/status").isFile) return false
        if (!File(staging, "usr/bin/apt-get").isFile) return false

        val marker = File(staging, STAGING_RESUME_MARKER)
        if (marker.isFile) {
            return marker.readText().contains("rootfsSha256=${rootfsAsset.sha256}")
        }

        // Accept a valid staging tree from the immediately preceding failed build
        // so an app update can resume instead of throwing away hundreds of packages.
        return allowLegacyFailedStaging
    }

    private fun writeStagingMarker(
        staging: File,
        rootfsAsset: RuntimeAsset,
    ) {
        File(staging, STAGING_RESUME_MARKER).writeText(
            "rootfsSha256=${rootfsAsset.sha256}\n",
        )
    }

    private fun adoptStagingAptCache(staging: File) {
        val guestArchives = File(staging, "var/cache/apt/archives")
        if (!guestArchives.isDirectory) return

        paths.aptArchivesDir.mkdirs()
        File(paths.aptArchivesDir, "partial").mkdirs()

        guestArchives.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == "deb" }
            ?.forEach { source ->
                val target = File(paths.aptArchivesDir, source.name)
                if (!target.isFile || target.length() != source.length()) {
                    source.copyTo(target, overwrite = true)
                }
            }
    }

    private fun repairLegacyFailedPackages(staging: File) {
        val result = installRunner.exec(
            command = """
                set -e
                if dpkg-query -W -f='${db:Status-Abbrev}' brave-browser 2>/dev/null \
                    | grep -qv '^ii '; then
                    dpkg --remove --force-remove-reinstreq brave-browser >/dev/null 2>&1 || true
                fi
                rm -f \
                    /var/lib/dpkg/lock \
                    /var/lib/dpkg/lock-frontend \
                    /var/cache/apt/archives/lock
                dpkg --configure -a || true
                apt-get -f install -y || true
            """.trimIndent(),
            timeoutSeconds = 300,
            rootfs = staging,
            fakeRoot = true,
        )
        journal.command("Repairing resumable staging package state", result)
    }

    private fun markerContents(state: String): String =
        buildString {
            appendLine("runtime=1")
            appendLine("state=$state")
            appendLine("device=${android.os.Build.DEVICE}")
            appendLine("uid=${android.os.Process.myUid()}")
            appendLine("abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
        }

    private fun requireAsset(
        assets: Map<RuntimeAssetKind, File>,
        kind: RuntimeAssetKind,
    ): File = assets[kind] ?: error("Missing runtime asset: $kind")
}
