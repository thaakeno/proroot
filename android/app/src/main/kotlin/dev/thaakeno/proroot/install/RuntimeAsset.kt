package dev.thaakeno.proroot.install

import java.io.File

enum class RuntimeAssetKind {
    ROOTFS,
    MESA_OVERLAY,
    ANLAND_GUEST,
    KWIN_PACKAGES,
    XWAYLAND_PACKAGE,
    BRAVE,
}

data class RuntimeAsset(
    val id: String,
    val kind: RuntimeAssetKind,
    val url: String,
    val sha256: String,
    val size: Long,
    val fileName: String,
) {
    fun cacheFile(cacheDir: File): File = File(cacheDir, fileName)
}
