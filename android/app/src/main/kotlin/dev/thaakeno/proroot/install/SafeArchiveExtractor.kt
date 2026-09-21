package dev.thaakeno.proroot.install

import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

class SafeArchiveExtractor {
    fun extractTarXz(archive: File, destination: File) {
        FileInputStream(archive).buffered(256 * 1024).use { input ->
            XZInputStream(input).use { compressed ->
                TarArchiveInputStream(BufferedInputStream(compressed, 256 * 1024)).use { tar ->
                    extractTar(tar, destination)
                }
            }
        }
    }

    fun extractTarGz(archive: File, destination: File) {
        FileInputStream(archive).buffered(256 * 1024).use { input ->
            GzipCompressorInputStream(input).use { compressed ->
                TarArchiveInputStream(BufferedInputStream(compressed, 256 * 1024)).use { tar ->
                    extractTar(tar, destination)
                }
            }
        }
    }

    fun extractZip(archive: File, destination: File) {
        destination.mkdirs()
        ZipInputStream(FileInputStream(archive).buffered(256 * 1024)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = safeTarget(destination, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered(256 * 1024).use { output ->
                        zip.copyTo(output, 256 * 1024)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun extractTar(tar: TarArchiveInputStream, destination: File) {
        destination.mkdirs()
        val hardLinks = mutableListOf<Pair<File, String>>()

        while (true) {
            val entry = tar.nextTarEntry ?: break
            val target = safeTarget(destination, entry.name)

            when {
                entry.isDirectory -> {
                    target.mkdirs()
                    applyMode(target, entry)
                }
                entry.isSymbolicLink -> {
                    target.parentFile?.mkdirs()
                    target.delete()
                    Files.createSymbolicLink(target.toPath(), Path.of(entry.linkName))
                }
                entry.isLink -> {
                    target.parentFile?.mkdirs()
                    hardLinks += target to entry.linkName
                }
                entry.isFile -> {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered(256 * 1024).use { output ->
                        tar.copyTo(output, 256 * 1024)
                    }
                    applyMode(target, entry)
                }
            }
        }

        hardLinks.forEach { (target, linkName) ->
            val source = safeTarget(destination, linkName)
            target.delete()
            Files.createLink(target.toPath(), source.toPath())
        }
    }

    private fun safeTarget(root: File, member: String): File {
        require(member.isNotBlank()) { "Archive contains a blank path" }
        require(!member.startsWith('/')) { "Archive contains absolute path: $member" }
        val canonicalRoot = root.canonicalFile
        val target = File(root, member).canonicalFile
        require(
            target.path == canonicalRoot.path ||
                target.path.startsWith(canonicalRoot.path + File.separator),
        ) { "Archive entry escapes root: $member" }
        return target
    }

    private fun applyMode(file: File, entry: TarArchiveEntry) {
        runCatching { Os.chmod(file.absolutePath, entry.mode and 0xFFF) }
    }
}
