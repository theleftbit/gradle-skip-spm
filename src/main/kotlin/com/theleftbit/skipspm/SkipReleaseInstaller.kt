package com.theleftbit.skipspm

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream

/** Exact official releases live beside each other; Homebrew's global link is untouched. */
internal class SkipReleaseInstaller(
    private val root: File,
    private val readVersion: (File) -> String?,
    private val platform: String = platformName(),
    private val download: (String, File) -> Unit = ::downloadFile,
) {
    fun select(version: String, offline: Boolean): File? {
        // Prereleases can still be reused from Homebrew, but never downloaded automatically.
        if (!Regex("""\d+\.\d+\.\d+""").matches(version)) return null
        val directory = File(root, "$platform/$version")
        fun cached(): File? = File(directory, "skip.artifactbundle/bin/skip")
            .takeIf { it.canExecute() && readVersion(it) == version }
        cached()?.let { return it }
        if (offline) return null
        directory.parentFile.mkdirs()
        // Serialize installers across Gradle processes, then publish a complete directory atomically.
        synchronized(SkipReleaseInstaller::class.java) {
            FileChannel.open(File(directory.parentFile, "$version.lock").toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    cached()?.let { return it }
                    val staging = Files.createTempDirectory(directory.parentFile.toPath(), "$version-").toFile()
                    try {
                        val metadata = File(staging, "release.json")
                        download("https://api.github.com/repos/skiptools/skip/releases/tags/$version", metadata)
                        val release = JsonSlurper().parse(metadata) as? Map<*, *>
                        val name = "skip-$platform.zip"
                        val asset = (release?.get("assets") as? List<*>)?.filterIsInstance<Map<*, *>>()
                            ?.singleOrNull { it["name"] == name }
                        val digest = (asset?.get("digest") as? String)?.removePrefix("sha256:")
                        if (digest == null || !Regex("[0-9a-f]{64}").matches(digest)) {
                            throw GradleException("skipSpm: official Skip $version has no SHA-256 digest for $name.")
                        }
                        val archive = File(staging, name)
                        download("https://github.com/skiptools/skip/releases/download/$version/$name", archive)
                        val hash = MessageDigest.getInstance("SHA-256")
                        archive.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                hash.update(buffer, 0, count)
                            }
                        }
                        if (hash.digest().joinToString("") { "%02x".format(it) } != digest) {
                            throw GradleException("skipSpm: SHA-256 verification failed for Skip $version ($name).")
                        }
                        val unpacked = File(staging, "unpacked").apply { mkdirs() }
                        ZipInputStream(archive.inputStream().buffered()).use { zip ->
                            while (true) {
                                val entry = zip.nextEntry ?: break
                                val target = unpacked.toPath().resolve(entry.name).normalize()
                                if (!target.startsWith(unpacked.toPath())) {
                                    throw GradleException("skipSpm: invalid path in Skip release archive.")
                                }
                                if (entry.isDirectory) Files.createDirectories(target)
                                else {
                                    Files.createDirectories(target.parent)
                                    Files.copy(zip, target)
                                    // The bundle contains a launcher and platform binaries (no symlinks).
                                    if (!target.toFile().setExecutable(true)) {
                                        throw GradleException("skipSpm: cannot make $target executable.")
                                    }
                                }
                            }
                        }
                        val executable = File(unpacked, "skip.artifactbundle/bin/skip")
                        if (!executable.canExecute() || readVersion(executable) != version) {
                            throw GradleException("skipSpm: downloaded CLI does not report Skip $version.")
                        }
                        if (directory.exists()) directory.deleteRecursively()
                        Files.move(unpacked.toPath(), directory.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        return cached() ?: throw GradleException("skipSpm: installed Skip $version is unusable.")
                    } catch (failure: Exception) {
                        throw GradleException("skipSpm: could not prepare exact Skip CLI $version: ${failure.message}", failure)
                    } finally {
                        staging.deleteRecursively()
                    }
                }
            }
        }
    }
}

private fun platformName(): String = when {
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos"
    System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> "linux"
    else -> throw GradleException("skipSpm: automatic Skip CLI installation supports macOS and Linux.")
}

private fun downloadFile(url: String, destination: File) {
    val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(2))
        .header("User-Agent", "gradle-skip-spm").GET().build()
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL).build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    response.body().use { input ->
        if (response.statusCode() != 200) {
            throw GradleException("skipSpm: downloading $url returned HTTP ${response.statusCode()}.")
        }
        destination.outputStream().use { input.copyTo(it) }
    }
}
