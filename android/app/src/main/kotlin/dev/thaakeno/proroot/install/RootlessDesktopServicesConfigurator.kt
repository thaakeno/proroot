package dev.thaakeno.proroot.install

import android.system.Os
import java.io.File

class RootlessDesktopServicesConfigurator(
    private val desktopUser: String = "linux",
) {
    fun configure(rootfs: File) {
        val serviceDir = File(rootfs, "etc/dbus-1/system-services").apply { mkdirs() }
        writeConfig(
            File(serviceDir, "org.freedesktop.PolicyKit1.service"),
            """
            [D-BUS Service]
            Name=org.freedesktop.PolicyKit1
            Exec=/usr/lib/polkit-1/polkitd --no-debug
            """.trimIndent() + "\n",
        )
        writeConfig(
            File(serviceDir, "org.freedesktop.PackageKit.service"),
            """
            [D-BUS Service]
            Name=org.freedesktop.PackageKit
            Exec=/usr/libexec/packagekitd
            """.trimIndent() + "\n",
        )

        val rulesDir = File(rootfs, "etc/polkit-1/rules.d").apply { mkdirs() }
        writeConfig(
            File(rulesDir, "49-proroot-package-management.rules"),
            """
            polkit.addRule(function(action, subject) {
                if (subject.user === "$desktopUser" &&
                    action.id.indexOf("org.freedesktop.packagekit.") === 0) {
                    return polkit.Result.YES;
                }
            });
            """.trimIndent() + "\n",
        )
    }

    private fun writeConfig(target: File, content: String) {
        target.parentFile?.mkdirs()
        target.writeText(content)
        Os.chmod(target.absolutePath, 0x1A4)
    }
}
