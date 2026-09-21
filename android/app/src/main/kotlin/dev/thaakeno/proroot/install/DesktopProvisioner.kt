package dev.thaakeno.proroot.install

import dev.thaakeno.proroot.runtime.ProrootRunner
import java.io.File

class DesktopProvisioner(
    private val runner: ProrootRunner,
    private val desktopUid: Int,
) {
    fun provisionBase(rootfs: File) {
        prepareConfiguration(rootfs)
        runChecked(rootfs, "apt-get update")
        runChecked(rootfs, """
            DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
              ca-certificates curl wget gnupg apt-transport-https locales sudo util-linux \
              dbus dbus-x11 dbus-user-session policykit-1 packagekit upower \
              python3-dbus python3-gi gir1.2-glib-2.0 \
              xdg-user-dirs xdg-utils desktop-file-utils shared-mime-info \
              xdg-desktop-portal xdg-desktop-portal-kde \
              kde-plasma-desktop plasma-workspace plasma-discover systemsettings \
              breeze breeze-icon-theme kde-config-gtk-style kio-extras \
              konsole dolphin kate ark okular spectacle gwenview kcalc \
              xwayland libgtk-3-bin \
              pipewire pipewire-pulse wireplumber \
              fonts-noto fonts-noto-cjk fonts-noto-color-emoji fonts-liberation \
              firefox-esr mesa-utils vulkan-tools glmark2 \
              libreoffice gimp vlc \
              build-essential cmake pkg-config python3 python3-pip nodejs npm \
              git openssh-client rsync file procps iproute2 net-tools jq ripgrep fd-find \
              htop nano vim less unzip xz-utils
        """.trimIndent())

        runChecked(rootfs, """
            set -e

            if getent group linux >/dev/null 2>&1; then
                current_gid="$(getent group linux | cut -d: -f3)"
                if [ "$current_gid" != "$desktopUid" ]; then
                    groupmod -g $desktopUid linux
                fi
            else
                groupadd -g $desktopUid linux
            fi

            if id linux >/dev/null 2>&1; then
                current_uid="$(id -u linux)"
                if [ "$current_uid" != "$desktopUid" ]; then
                    usermod -u $desktopUid linux
                fi
                usermod -g linux -s /bin/bash linux
            else
                useradd -m -u $desktopUid -g linux -s /bin/bash linux
            fi

            install -d -m 0755 -o linux -g linux /home/linux
            chown -R linux:linux /home/linux
            install -d -m 1777 /tmp /dev/shm /tmp/.X11-unix

            dbus-uuidgen --ensure=/etc/machine-id
            rm -f /var/lib/dbus/machine-id
            ln -s /etc/machine-id /var/lib/dbus/machine-id

            update-desktop-database /usr/share/applications || true
            update-mime-database /usr/share/mime || true
        """.trimIndent())

        installPinnedDesktopStack(rootfs)
        installBrave(rootfs)
        installVsCode(rootfs)
        configureDesktop(rootfs)
        protectGraphicsStack(rootfs)
    }

    fun finalizeGraphicsAndVerify(rootfs: File) {
        runChecked(rootfs, "ldconfig")
        runChecked(rootfs, """
            set -e
            test "$(id -u)" = "$desktopUid"
            test -x /usr/bin/startplasma-wayland
            test -x /usr/bin/konsole
            test -x /usr/bin/dolphin
            test -x /usr/bin/firefox-esr
            test -x /usr/bin/code
            test -x /usr/bin/brave-browser
            test -x /usr/local/lib/proroot/start-desktop.sh
            test -c /dev/kgsl-3d0
            test -r /proc/stat
            test -r /proc/uptime
            test -r /proc/vmstat
            test -r /proc/bus/pci/devices
            test -w /dev/shm
            touch /dev/shm/.proroot-shm-test
            rm -f /dev/shm/.proroot-shm-test

            MESA_LOADER_DRIVER_OVERRIDE=kgsl \
            TURNIP_KMD=kgsl \
            GALLIUM_DRIVER=freedreno \
              vulkaninfo --summary 2>&1 | tee /tmp/proroot-vulkan-summary.txt

            grep -Eiq 'Adreno|turnip' /tmp/proroot-vulkan-summary.txt
            brave-browser --version
            firefox-esr --version
        """.trimIndent(), fakeRoot = false)
    }

    private fun prepareConfiguration(rootfs: File) {
        File(rootfs, "etc/resolv.conf").apply {
            delete()
            parentFile?.mkdirs()
            writeText(
                "nameserver 1.1.1.1\n" +
                    "nameserver 8.8.8.8\n" +
                    "options timeout:2 attempts:2\n",
            )
        }

        File(rootfs, "etc/locale.gen").appendText("\nen_US.UTF-8 UTF-8\n")

        File(rootfs, "etc/apt/apt.conf.d/80proroot").apply {
            parentFile?.mkdirs()
            writeText(
                """
                Acquire::Retries "4";
                Acquire::http::Pipeline-Depth "10";
                Acquire::https::Pipeline-Depth "10";
                Dpkg::Use-Pty "0";
                APT::Install-Recommends "0";
                """.trimIndent() + "\n",
            )
        }
    }

    private fun installPinnedDesktopStack(rootfs: File) {
        runChecked(rootfs, """
            set -e
            apt-get install -y /opt/proroot-packages/anland/anland.deb
            apt-get install -y /opt/proroot-packages/xwayland/xwayland.deb
            find /opt/proroot-packages/kwin -type f -name '*.deb' -print0 \
              | xargs -0 -r apt-get install -y
        """.trimIndent())
    }

    private fun installBrave(rootfs: File) {
        runChecked(rootfs, """
            set -e
            DEBIAN_FRONTEND=noninteractive apt-get install -y /opt/proroot-packages/brave/brave.deb
            update-desktop-database /usr/share/applications || true
        """.trimIndent())
    }

    private fun installVsCode(rootfs: File) {
        runChecked(rootfs, """
            set -e
            install -d -m 0755 /etc/apt/keyrings
            curl -fsSL https://packages.microsoft.com/keys/microsoft.asc \
              | gpg --dearmor --yes -o /etc/apt/keyrings/packages.microsoft.gpg
            chmod 0644 /etc/apt/keyrings/packages.microsoft.gpg
            printf '%s\n' \
              'deb [arch=arm64 signed-by=/etc/apt/keyrings/packages.microsoft.gpg] https://packages.microsoft.com/repos/code stable main' \
              >/etc/apt/sources.list.d/vscode.list
            apt-get update
            DEBIAN_FRONTEND=noninteractive apt-get install -y code
        """.trimIndent())
    }

    private fun configureDesktop(rootfs: File) {
        runChecked(rootfs, """
            set -e
            locale-gen en_US.UTF-8
            update-locale LANG=en_US.UTF-8
            chown -R linux:linux /home/linux

            install -d -m 0755 /usr/local/bin
            cat >/usr/local/bin/proroot-gpu-info <<'EOF'
            #!/bin/sh
            set -eu
            export MESA_LOADER_DRIVER_OVERRIDE=kgsl
            export TURNIP_KMD=kgsl
            export GALLIUM_DRIVER=freedreno
            export FD_FORCE_KGSL=1
            exec vulkaninfo --summary
            EOF
            chmod 0755 /usr/local/bin/proroot-gpu-info

            update-desktop-database /usr/share/applications || true
        """.trimIndent())
    }

    private fun protectGraphicsStack(rootfs: File) {
        File(rootfs, "etc/apt/preferences.d/hold-proroot-graphics").apply {
            parentFile?.mkdirs()
            writeText(
                """
                Package: xwayland kwin-common kwin-data kwin-wayland libkwin6 libegl-mesa0 libgbm1 libgl1-mesa-dri libglx-mesa0 mesa-libgallium mesa-vulkan-drivers
                Pin: origin *
                Pin-Priority: -1
                """.trimIndent() + "\n",
            )
        }
    }

    private fun runChecked(rootfs: File, command: String, fakeRoot: Boolean = true) {
        val result = runner.exec(
            command = command,
            timeoutSeconds = 1_800,
            rootfs = rootfs,
            fakeRoot = fakeRoot,
        )
        check(result.successful) {
            "Provisioning failed (exit ${result.exitCode}):\n${result.output.takeLast(12_000)}"
        }
    }
}
