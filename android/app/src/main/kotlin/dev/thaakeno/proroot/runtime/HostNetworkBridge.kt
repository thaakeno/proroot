package dev.thaakeno.proroot.runtime

import android.content.Context
import android.net.ConnectivityManager
import java.io.File

class HostNetworkBridge(context: Context) {
    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun sync(rootfs: File) {
        if (!rootfs.isDirectory) return

        val servers = runCatching {
            val network = connectivity.activeNetwork ?: return@runCatching emptyList()
            connectivity.getLinkProperties(network)
                ?.dnsServers
                ?.mapNotNull { it.hostAddress }
                ?.distinct()
                .orEmpty()
        }.getOrDefault(emptyList())

        val resolv = File(rootfs, "etc/resolv.conf")
        val usable = servers.ifEmpty {
            if (resolv.isFile && resolv.readText().lineSequence().any { it.trim().startsWith("nameserver ") }) {
                return
            }
            listOf("1.1.1.1", "8.8.8.8")
        }

        resolv.parentFile?.mkdirs()
        val content = buildString {
            usable.forEach { appendLine("nameserver $it") }
            appendLine("options timeout:2 attempts:2")
        }
        val staging = File(resolv.parentFile, "resolv.conf.new")
        staging.writeText(content)
        if (!staging.renameTo(resolv)) {
            resolv.writeText(content)
            staging.delete()
        }
    }
}
