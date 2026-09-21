package dev.thaakeno.proroot.install

object AssetCatalog {
    val all: List<RuntimeAsset> = listOf(
        RuntimeAsset(
            id = "debian-trixie-arm64",
            kind = RuntimeAssetKind.ROOTFS,
            url = "https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-aarch64-pd-v4.29.0.tar.xz",
            sha256 = "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918",
            size = 35_409_704,
            fileName = "debian-trixie-aarch64.tar.xz",
        ),
        RuntimeAsset(
            id = "mesa-adreno-26.3.0",
            kind = RuntimeAssetKind.MESA_OVERLAY,
            url = "https://github.com/lfdevs/mesa-for-android-container/releases/download/mesa-26.3.0-devel-20260824/mesa-for-android-container_26.3.0-devel-20260824_debian_trixie_arm64.tar.gz",
            sha256 = "c014cf66bdbff96417ee30d34f006cf51df64ae04893d599711b0b6b73b52ccf",
            size = 11_648_933,
            fileName = "mesa-adreno840-debian13.tar.gz",
        ),
        RuntimeAsset(
            id = "anland-guest-5.13.3",
            kind = RuntimeAssetKind.ANLAND_GUEST,
            url = "https://github.com/lfdevs/anland-termux/releases/download/5.13.3/anland_5.13.3_aarch64.deb",
            sha256 = "62cc21942692377aff64f4e7d6d8cd110c4ed1b49e524c95584c98c7c222d493",
            size = 7_232,
            fileName = "anland_5.13.3_aarch64.deb",
        ),
        RuntimeAsset(
            id = "kwin-anland-debian13",
            kind = RuntimeAssetKind.KWIN_PACKAGES,
            url = "https://github.com/lfdevs/anland-termux/releases/download/5.13.3/kwin_anland-5.13-debian-4_6.3.6-95.zip",
            sha256 = "56ce1da27b640c977bad5ca0b7b13196e609b5fc419703429e51805ec05e4ee4",
            size = 10_604_606,
            fileName = "kwin-anland-debian13.zip",
        ),
        RuntimeAsset(
            id = "xwayland-anland-debian13",
            kind = RuntimeAssetKind.XWAYLAND_PACKAGE,
            url = "https://github.com/lfdevs/anland-termux/releases/download/5.13.3/xwayland_24.1.6-91_arm64.deb",
            sha256 = "59f9c7486d6a10ad50a13622bf1d1bbf5accd015d630e4b2b0152a80577dcc64",
            size = 825_848,
            fileName = "xwayland_24.1.6-91_arm64.deb",
        ),
        RuntimeAsset(
            id = "brave-arm64-1.95.104",
            kind = RuntimeAssetKind.BRAVE,
            url = "https://github.com/brave/brave-browser/releases/download/v1.95.104/brave-browser-1.95.104-linux-arm64.zip",
            sha256 = "9f2dab1cd328cdaa88302be319bd269d59a8a84c8dfc6ccaa71287b8c0629ac2",
            size = 187_752_174,
            fileName = "brave-browser-1.95.104-linux-arm64.zip",
        ),
    )
}
