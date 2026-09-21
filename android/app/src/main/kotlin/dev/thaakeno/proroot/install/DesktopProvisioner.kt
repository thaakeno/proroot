package dev.thaakeno.proroot.install

import dev.thaakeno.proroot.runtime.ProrootRunner
import java.io.File

class DesktopProvisioner(private val runner: ProrootRunner) {
    fun provisionBase(rootfs: File) {
        prepareConfiguration(rootfs)
        runChecked(rootfs, "apt-get update")
        runChecked(rootfs, """
            DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends               ca-certificates curl wget gnupg apt-transport-https locales sudo util-linux               dbus dbus-x11 dbus-user-session policykit-1 packagekit               kde-plasma-desktop plasma-workspace plasma-discover systemsettings               breeze breeze-icon-theme kde-config-gtk-style kio-extras               konsole dolphin kate ark okular spectacle               xwayland libgtk-3-bin xdg-utils               pipewire pipewire-pulse wireplumber               fonts-noto fonts-noto-cjk fonts-noto-color-emoji               firefox-esr mesa-utils vulkan-tools glmark2               git openssh-client rsync file procps iproute2 net-tools               htop nano vim less unzip xz-utils
        """.trimIndent())

        runChecked(rootfs, """
            id -u linux >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash linux
            install -d -m 0700 -o 1000 -g 1000 /run/user/1000
            install -d -m 1777 /tmp/.X11-unix
            printf 'linux ALL=(ALL) NOPASSWD: ALL
' >/etc/sudoers.d/90-linux
            chmod 0440 /etc/sudoers.d/90-linux
            dbus-uuidgen --ensure=/etc/machine-id
            rm -f /var/lib/dbus/machine-id
            ln -s /etc/machine-id /var/lib/dbus/machine-id
        """.trimIndent())

        installPinnedDesktopStack(rootfs)
        installVsCode(rootfs)
        configureDesktop(rootfs)
        protectGraphicsStack(rootfs)
    }

    fun finalizeGraphicsAndVerify(rootfs: File) {
        runChecked(rootfs, "ldconfig")
        runChecked(rootfs, """
            set -e
            test -x /usr/bin/startplasma-wayland
            test -x /usr/bin/konsole
            test -x /usr/bin/dolphin
            test -x /usr/bin/firefox-esr
            test -x /usr/bin/code
            test -x /usr/local/bin/brave-browser
            test -c /dev/kgsl-3d0
            MESA_LOADER_DRIVER_OVERRIDE=kgsl TURNIP_KMD=kgsl GALLIUM_DRIVER=freedreno               vulkaninfo --summary 2>&1 | tee /tmp/proroot-vulkan-summary.txt
            grep -Eiq 'Adreno|turnip' /tmp/proroot-vulkan-summary.txt
        """.trimIndent())
    }

    private fun prepareConfiguration(rootfs: File) {
        File(rootfs, "etc/resolv.conf").apply {
            delete()
            parentFile?.mkdirs()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\noptions timeout:2 attempts:2\n")
        }
        File(rootfs, "etc/locale.gen").appendText("\nen_US.UTF-8 UTF-8\n")
        File(rootfs, "etc/apt/apt.conf.d/80proroot").apply {
            parentFile?.mkdirs()
            writeText("""
                Acquire::Retries "4";
                Acquire::http::Pipeline-Depth "10";
                Acquire::https::Pipeline-Depth "10";
                Dpkg::Use-Pty "0";
                APT::Install-Recommends "0";
            """.trimIndent() + "\n")
        }
    }

    private fun installPinnedDesktopStack(rootfs: File) {
        runChecked(rootfs, """
            apt-get install -y /opt/proroot-packages/anland/anland.deb
            apt-get install -y /opt/proroot-packages/xwayland/xwayland.deb
            find /opt/proroot-packages/kwin -type f -name '*.deb' -print0               | xargs -0 -r apt-get install -y
        """.trimIndent())
    }

    private fun installVsCode(rootfs: File) {
        runChecked(rootfs, """
            install -d -m 0755 /etc/apt/keyrings
            curl -fsSL https://packages.microsoft.com/keys/microsoft.asc               | gpg --dearmor --yes -o /etc/apt/keyrings/packages.microsoft.gpg
            chmod 0644 /etc/apt/keyrings/packages.microsoft.gpg
            printf '%s
'               'deb [arch=arm64 signed-by=/etc/apt/keyrings/packages.microsoft.gpg] https://packages.microsoft.com/repos/code stable main'               >/etc/apt/sources.list.d/vscode.list
            apt-get update
            DEBIAN_FRONTEND=noninteractive apt-get install -y code
        """.trimIndent())
    }

    private fun configureDesktop(rootfs: File) {
        runChecked(rootfs, """
            locale-gen en_US.UTF-8
            update-locale LANG=en_US.UTF-8
            install -d -m 0755 /home/linux/.config
            chown -R linux:linux /home/linux
            cat >/usr/local/bin/proroot-gpu-info <<'EOF'
            #!/bin/sh
            set -eu
            export MESA_LOADER_DRIVER_OVERRIDE=kgsl
            export TURNIP_KMD=kgsl
            export GALLIUM_DRIVER=freedreno
            exec vulkaninfo --summary
            EOF
            chmod 0755 /usr/local/bin/proroot-gpu-info
        """.trimIndent())
    }

    private fun protectGraphicsStack(rootfs: File) {
        File(rootfs, "etc/apt/preferences.d/hold-anland-stack").apply {
            parentFile?.mkdirs()
            writeText("""
                Package: xwayland kwin-common kwin-data kwin-wayland libkwin6 libegl-mesa0 libgbm1 libgl1-mesa-dri libglx-mesa0 mesa-libgallium mesa-vulkan-drivers
                Pin: origin *
                Pin-Priority: -1
            """.trimIndent() + "\n")
        }
    }

    private fun runChecked(rootfs: File, command: String) {
        val result = runner.exec(command, timeoutSeconds = 1_800, rootfs = rootfs)
        check(result.successful) {
            "Provisioning failed (exit ${result.exitCode}):\n${result.output.takeLast(12_000)}"
        }
    }
}
