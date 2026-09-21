package dev.thaakeno.proroot.install

import dev.thaakeno.proroot.runtime.CommandResult
import dev.thaakeno.proroot.runtime.GuestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max

class DesktopProvisioner(
    private val runner: GuestRunner,
    private val desktopUid: Int,
    private val journal: InstallJournal,
) {
    private val rootlessServices = RootlessDesktopServicesConfigurator()

    fun provisionBase(
        rootfs: File,
        onProgress: (ProvisioningStage) -> Unit = {},
    ) {
        onProgress(ProvisioningStage(0.45, "Checking installer runtime", 900))
        runChecked(rootfs, "/bin/true")

        onProgress(ProvisioningStage(0.46, "Preparing Debian package sources", 890))
        prepareConfiguration(rootfs)
        runChecked(rootfs, "apt-get update")

        val packageGroups = listOf(
            PackageGroup(
                start = 0.50,
                end = 0.56,
                message = "Installing Debian base services",
                expectedSeconds = 90,
                etaAfterSeconds = 770,
                packages = """
                    ca-certificates curl wget gnupg apt-transport-https locales sudo util-linux
                    dbus dbus-bin dbus-x11 dbus-user-session polkitd pkexec packagekit packagekit-tools upower
                    python3-dbus python3-gi gir1.2-glib-2.0
                    xdg-user-dirs xdg-utils desktop-file-utils shared-mime-info
                    xdg-desktop-portal xdg-desktop-portal-kde
                """.trimIndent(),
            ),
            PackageGroup(
                start = 0.56,
                end = 0.64,
                message = "Installing KDE Plasma desktop",
                expectedSeconds = 220,
                etaAfterSeconds = 550,
                packages = """
                    kde-plasma-desktop plasma-workspace plasma-discover systemsettings libkscreen-bin
                    breeze breeze-icon-theme kde-config-gtk-style kio-extras
                    konsole dolphin kate ark okular kde-spectacle gwenview kcalc
                    xwayland libgtk-3-bin pipewire pipewire-pulse wireplumber
                """.trimIndent(),
            ),
            PackageGroup(
                start = 0.64,
                end = 0.70,
                message = "Installing desktop applications",
                expectedSeconds = 160,
                etaAfterSeconds = 390,
                packages = """
                    fonts-noto fonts-noto-cjk fonts-noto-color-emoji fonts-liberation
                    firefox-esr mesa-utils vulkan-tools glmark2
                    libreoffice gimp vlc
                """.trimIndent(),
            ),
            PackageGroup(
                start = 0.70,
                end = 0.74,
                message = "Installing development tools",
                expectedSeconds = 100,
                etaAfterSeconds = 290,
                packages = """
                    build-essential cmake pkg-config python3 python3-pip nodejs npm
                    git openssh-client rsync file procps iproute2 net-tools jq ripgrep fd-find
                    htop nano vim less unzip xz-utils
                """.trimIndent(),
            ),
        )

        val allPackages = packageGroups
            .flatMap { group -> group.packages.split(Regex("\\s+")) }
            .filter { it.isNotBlank() }
            .joinToString(" ")

        onProgress(ProvisioningStage(0.49, "Validating Debian desktop packages", 860))
        runChecked(
            rootfs,
            """
            set -e
            packages='$allPackages'
            missing=''
            for package in ${'$'}packages; do
                if ! apt-cache show "${'$'}package" >/dev/null 2>&1; then
                    missing="${'$'}missing ${'$'}package"
                fi
            done
            if [ -n "${'$'}missing" ]; then
                printf 'Missing Debian packages:%s\n' "${'$'}missing" >&2
                exit 100
            fi
            """.trimIndent(),
        )

        packageGroups.forEach { group ->
            installPackageGroup(rootfs, group, onProgress)
        }

        onProgress(ProvisioningStage(0.75, "Creating persistent Linux desktop user", 270))
        runChecked(rootfs, """
            set -e

            if getent group linux >/dev/null 2>&1; then
                if [ "$(getent group linux | cut -d: -f3)" != "$desktopUid" ]; then
                    groupmod -g $desktopUid linux
                fi
            else
                groupadd -g $desktopUid linux
            fi

            if id linux >/dev/null 2>&1; then
                if [ "$(id -u linux)" != "$desktopUid" ]; then
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

        onProgress(ProvisioningStage(0.77, "Normalizing staged ARM64 packages", 260))
        runChecked(
            rootfs,
            "/bin/bash /usr/local/lib/proroot/normalize-staged-debs.sh /opt/proroot-packages",
        )

        onProgress(ProvisioningStage(0.78, "Installing Anland, KWin and XWayland", 250))
        installPinnedDesktopStack(rootfs)

        onProgress(ProvisioningStage(0.82, "Installing Brave Browser", 200))
        installBrave(rootfs)

        onProgress(ProvisioningStage(0.85, "Installing Visual Studio Code", 160))
        installVsCode(rootfs)

        onProgress(ProvisioningStage(0.88, "Configuring rootless desktop services", 100))
        rootlessServices.configure(rootfs)

        onProgress(ProvisioningStage(0.90, "Configuring the Linux desktop", 75))
        configureDesktop(rootfs)

        onProgress(ProvisioningStage(0.91, "Protecting the verified graphics stack", 55))
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
            test -x /usr/bin/kscreen-doctor
            test -x /usr/bin/firefox-esr
            test -x /usr/bin/code
            test -x /usr/bin/brave-browser-stable
            test -x /usr/lib/polkit-1/polkitd
            test -x /usr/libexec/packagekitd
            test -x /usr/bin/pkcon
            test -f /etc/dbus-1/system-services/org.freedesktop.PolicyKit1.service
            test -f /etc/dbus-1/system-services/org.freedesktop.PackageKit.service
            test -f /etc/polkit-1/rules.d/49-proroot-package-management.rules
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
            brave-browser-stable --version
            firefox-esr --version
        """.trimIndent(), fakeRoot = false)
    }

    private fun installPackageGroup(
        rootfs: File,
        group: PackageGroup,
        onProgress: (ProvisioningStage) -> Unit,
    ) {
        val packages = group.packages
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ")

        onProgress(
            ProvisioningStage(
                group.start,
                group.message,
                group.expectedSeconds + group.etaAfterSeconds,
            ),
        )

        val started = System.nanoTime()
        val finished = AtomicBoolean(false)
        val command = "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $packages"

        val heartbeat = thread(
            name = "installer-progress-heartbeat",
            isDaemon = true,
        ) {
            while (!finished.get()) {
                val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
                val fraction = (elapsed / group.expectedSeconds.toDouble()).coerceIn(0.0, 0.90)
                val progress = group.start + (group.end - group.start) * fraction
                val eta = max(
                    0L,
                    (group.expectedSeconds - elapsed.toLong()) + group.etaAfterSeconds,
                )
                onProgress(ProvisioningStage(progress, group.message, eta))
                try {
                    Thread.sleep(1_000L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        journal.commandStart(command)
        val result = try {
            runner.execStreaming(
                command = command,
                timeoutSeconds = 1_800,
                rootfs = rootfs,
                fakeRoot = true,
            ) { line ->
                journal.commandOutput(line)
            }
        } finally {
            finished.set(true)
            heartbeat.interrupt()
            heartbeat.join(1_500L)
        }
        journal.commandEnd(result)
        checkResult(result)

        onProgress(
            ProvisioningStage(
                group.end,
                group.message,
                group.etaAfterSeconds,
            ),
        )
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
        File(rootfs, "etc/apt/preferences.d/hold-proroot-graphics").delete()
        runChecked(rootfs, """
            set -e
            apt-mark hold \
              xwayland kwin-common kwin-data kwin-wayland libkwin6 \
              libegl-mesa0 libgbm1 libgl1-mesa-dri libglx-mesa0 \
              mesa-libgallium mesa-vulkan-drivers
        """.trimIndent())
    }

    private fun runChecked(rootfs: File, command: String, fakeRoot: Boolean = true) {
        val result = runner.exec(
            command = command,
            timeoutSeconds = 1_800,
            rootfs = rootfs,
            fakeRoot = fakeRoot,
        )
        journal.command(command, result)
        checkResult(result)
    }

    private fun checkResult(result: CommandResult) {
        check(result.successful) {
            "Provisioning failed via ${runner.runtimeId} (exit ${result.exitCode}). Full output is in install.log."
        }
    }

    private data class PackageGroup(
        val start: Double,
        val end: Double,
        val message: String,
        val expectedSeconds: Long,
        val etaAfterSeconds: Long,
        val packages: String,
    )
}
