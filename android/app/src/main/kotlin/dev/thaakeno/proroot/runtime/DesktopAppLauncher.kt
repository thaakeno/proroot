package dev.thaakeno.proroot.runtime

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import java.io.File
import java.util.concurrent.TimeUnit

class DesktopAppLauncher(
    private val paths: RuntimePaths,
) {
    private val desktopIdPattern = Regex("[A-Za-z0-9._+-]+")

    fun launch(desktopId: String) {
        require(desktopId.matches(desktopIdPattern)) { "Invalid desktop id" }

        val socketFile = File(
            paths.rootfs,
            "run/user/${Process.myUid()}/proroot-app-launcher.sock",
        )
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!socketFile.exists() && System.nanoTime() < deadline) {
            Thread.sleep(25)
        }
        check(socketFile.exists()) {
            "KDE app launcher is not ready yet"
        }

        LocalSocket().use { socket ->
            socket.connect(
                LocalSocketAddress(
                    socketFile.absolutePath,
                    LocalSocketAddress.Namespace.FILESYSTEM,
                ),
            )
            socket.soTimeout = 3_000
            // Keep the socket open while reading the server reply. Closing a
            // LocalSocket output stream can close the underlying connection.
            val writer = socket.outputStream.bufferedWriter(Charsets.UTF_8)
            writer.write(desktopId.removeSuffix(".desktop"))
            writer.newLine()
            writer.flush()

            val response = socket.inputStream
                .bufferedReader(Charsets.UTF_8)
                .readLine()
                .orEmpty()
            check(response == "OK") {
                response.removePrefix("ERR ").ifBlank {
                    "KDE app launcher rejected the request"
                }
            }
        }
    }
}
