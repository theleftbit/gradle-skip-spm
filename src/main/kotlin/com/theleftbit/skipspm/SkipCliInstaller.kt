package com.theleftbit.skipspm

import org.gradle.api.GradleException
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/** Installs official, versioned CLI binaries without changing the machine's Homebrew/PATH. */
internal object SkipCliInstaller {
    fun platform(os: String = System.getProperty("os.name"), arch: String = System.getProperty("os.arch")): String =
        when {
            os.startsWith("Mac", ignoreCase = true) -> "macos"
            os.startsWith("Linux", ignoreCase = true) -> when (arch) {
                "amd64", "x86_64" -> "x86_64-swift-linux-musl"
                "aarch64", "arm64" -> "aarch64-swift-linux-musl"
                else -> throw GradleException("skipSpm: automatic Skip CLI installation does not support Linux $arch.")
            }
            else -> throw GradleException("skipSpm: automatic Skip CLI installation does not support $os.")
        }

    fun install(
        version: String,
        cache: File,
        platform: String,
        offline: Boolean,
        readVersion: (File) -> String?,
        download: (String, File) -> Unit = ::downloadRelease,
    ): File {
        require(Regex("""\d+\.\d+\.\d+""").matches(version)) {
            "skipSpm: cannot automatically install non-release Skip version '$version'."
        }
        val directory = File(cache, "$version/$platform").apply { mkdirs() }
        val executable = File(directory, "skip")
        RandomAccessFile(File(directory, "install.lock"), "rw").use { lockFile ->
            lockFile.channel.awaitInstallLock().use {
                if (executable.canExecute() && readVersion(executable) == version) return executable
                if (offline) {
                    throw GradleException("skipSpm: Skip CLI $version is not cached. Run once without --offline to install it.")
                }
                val archive = Files.createTempFile(directory.toPath(), "download-", ".zip").toFile()
                val candidate = Files.createTempFile(directory.toPath(), "skip-", ".tmp").toFile()
                try {
                    val asset = if (platform == "macos") "skip-macos.zip" else "skip-linux.zip"
                    download("https://github.com/skiptools/skip/releases/download/$version/$asset", archive)
                    val entryName = "skip.artifactbundle/$platform/skip"
                    var found = false
                    ZipInputStream(archive.inputStream().buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.name == entryName && !entry.isDirectory) {
                                candidate.outputStream().use { zip.copyTo(it) }
                                found = true
                                break
                            }
                        }
                    }
                    check(found) { "Official release archive is missing $entryName" }
                    check(candidate.setExecutable(true)) { "Cannot make the downloaded CLI executable" }
                    check(readVersion(candidate) == version) { "Downloaded CLI does not report Skip version $version" }
                    Files.move(candidate.toPath(), executable.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    return executable
                } catch (e: Exception) {
                    throw GradleException("skipSpm: could not install Skip CLI $version: ${e.message}", e)
                } finally {
                    archive.delete()
                    candidate.delete()
                }
            }
        }
    }

    // A JVM monitor cannot coordinate separate plugin classloaders. File locks cover other
    // processes, but throw (rather than wait) when this JVM already holds the same lock.
    private fun FileChannel.awaitInstallLock(): FileLock {
        while (true) {
            try {
                return lock()
            } catch (_: OverlappingFileLockException) {
                Thread.sleep(50)
            }
        }
    }

    private fun downloadRelease(url: String, destination: File) {
        val connection = URI(url).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 120_000
        }
        connection.getInputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
    }
}
