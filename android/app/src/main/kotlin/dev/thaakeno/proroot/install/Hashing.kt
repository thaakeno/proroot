package dev.thaakeno.proroot.install

import java.io.File
import java.security.MessageDigest

object Hashing {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun verify(file: File, expected: String): Boolean =
        file.isFile && sha256(file).equals(expected, ignoreCase = true)
}
