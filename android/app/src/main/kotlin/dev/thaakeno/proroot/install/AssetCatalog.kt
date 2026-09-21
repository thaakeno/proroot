package dev.thaakeno.proroot.install

object AssetCatalog {
    val all: List<RuntimeAsset> = listOf(
        RuntimeAsset("debian-trixie-arm64", RuntimeAssetKind.ROOTFS,
            "https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-aarch64-pd-v4.29.0.tar.xz",
            "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918",
            35_409_704, "debian-trixie-aarch64.tar.xz"),
        RuntimeAsset("mesa-adreno-26.3.0", RuntimeAssetKind.MESA_OVERLAY,
            "https://github.com/lfdevs/mesa-for-android-container/releases/download/mesa-26.3.0-devel-20260824/mesa-for-android-container_26.3.0-devel-20260824_debian_trixie_arm64.tar.gz",
            "c014cf66bdbff96417ee30d34f006cf51df64ae04893d599711b0b6b73b52ccf",
            11_648_933, "mesa-adreno840-debian13.tar.gz"),
        RuntimeAsset("anland-guest-5.13.3", RuntimeAssetKind.ANLAND_GUEST,
            "https://github.com/lfdevs/anland-termux/releases/download/5.13.3/anland_5.13.3_aarch64.deb",
            "62cc21942692377aff64f4e7d6d8cd110c4ed1b49e524c95584c98c7c222d493",
            7_232, "anland_5.13.3_aarch64.deb"),
        RuntimeAsset("kwin-anland-debian13", RuntimeAssetKind.KWIN_PACKAGES,
            "https://github.com/lfdevs/anland-termux/releases/download/5.13.3/kwin_anland-5.13-debian-4_6.3.6-95.zip",
            "56ce1da27b640c977bad5ca0b7b13196e609b5fc419703429e51805ec05e4ee4",
            10_604_606, "kwin-anland-debian13.zip"),
        RuntimeAsset("xwayland-anland-debian13", RuntimeAssetKind.XWAYLAND_PACKAGE,
            "https://github.com/lfdevs/anland-termux/releases/download/5.13.3/xwayland_24.1.6-91_arm64.deb",
            "59f9c7486d6a10ad50a13622bf1d1bbf5accd015d630e4b2b0152a80577dcc64",
            825_848, "xwayland_24.1.6-91_arm64.deb"),
        RuntimeAsset("brave-arm64-1.95.104", RuntimeAssetKind.BRAVE,
            "https://github.com/brave/brave-browser/releases/download/v1.95.104/brave-browser_1.95.104_arm64.deb",
            "251ee83fa383db6106ae6140df0975689b226d15ceb96cdb4147cb90cd7c25ba",
            140_194_012, "brave-browser_1.95.104_arm64.deb"),
    )
}
