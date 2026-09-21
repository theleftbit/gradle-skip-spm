package com.theleftbit.skipspm

import org.gradle.api.GradleException
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkipCliInstallerTest {
    @TempDir
    lateinit var cache: File

    private fun archive(destination: File, content: String = "1.9.11", path: String = "skip.artifactbundle/macos/skip") {
        ZipOutputStream(destination.outputStream()).use {
            it.putNextEntry(ZipEntry(path))
            it.write(content.toByteArray())
            it.closeEntry()
        }
    }

    @Test
    fun `downloads exact release then reuses it offline`() {
        val cli = SkipCliInstaller.install("1.9.11", cache, "macos", false, { it.readText() }) { url, destination ->
            assertEquals("https://github.com/skiptools/skip/releases/download/1.9.11/skip-macos.zip", url)
            archive(destination)
        }
        assertTrue(cli.canExecute())
        assertEquals(cli, SkipCliInstaller.install("1.9.11", cache, "macos", true, { it.readText() }) { _, _ ->
            error("Cached CLI must not be downloaded again")
        })
    }

    @Test
    fun `uncached offline install fails before downloading`() {
        val failure = assertFailsWith<GradleException> {
            SkipCliInstaller.install("1.9.11", cache, "macos", true, { null }) { _, _ -> error("No network offline") }
        }
        assertTrue(failure.message!!.contains("without --offline"))
    }

    @Test
    fun `wrong release version never becomes the cached executable`() {
        assertFailsWith<GradleException> {
            SkipCliInstaller.install("1.9.11", cache, "macos", false, { it.readText() }) { _, destination ->
                archive(destination, "1.9.10")
            }
        }
        assertFalse(File(cache, "1.9.11/macos/skip").exists())
        assertEquals(listOf("install.lock"), File(cache, "1.9.11/macos").list()!!.toList())
    }

    @Test
    fun `failed download can be retried without accepting a partial install`() {
        assertFailsWith<GradleException> {
            SkipCliInstaller.install("1.9.11", cache, "macos", false, { it.readText() }) { _, destination ->
                destination.writeText("partial")
                error("network interrupted")
            }
        }
        val cli = SkipCliInstaller.install("1.9.11", cache, "macos", false, { it.readText() }) { _, destination -> archive(destination) }
        assertEquals("1.9.11", cli.readText())
    }

    @Test
    fun `archive cannot extract arbitrary paths`() {
        assertFailsWith<GradleException> {
            SkipCliInstaller.install("1.9.11", cache, "macos", false, { it.readText() }) { _, destination ->
                archive(destination, path = "../../escaped")
            }
        }
        assertFalse(File(cache, "escaped").exists())
        assertFalse(File(cache, "1.9.11/macos/skip").exists())
    }

    @Test
    fun `concurrent installs share one completed download`() {
        val downloads = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = (1..2).map {
                Callable {
                    SkipCliInstaller.install("1.9.11", cache, "macos", false, { it.readText() }) { _, destination ->
                        downloads.incrementAndGet()
                        archive(destination)
                    }
                }
            }
            val results = pool.invokeAll(tasks).map { it.get() }
            assertEquals(results[0], results[1])
            assertEquals(1, downloads.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `waits for a lock held elsewhere in the same JVM`() {
        val directory = File(cache, "1.9.11/macos").apply { mkdirs() }
        val started = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            RandomAccessFile(File(directory, "install.lock"), "rw").use { file ->
                val heldLock = file.channel.lock()
                val result = pool.submit(Callable {
                    started.countDown()
                    SkipCliInstaller.install("1.9.11", cache, "macos", false, { it.readText() }) { _, destination ->
                        archive(destination)
                    }
                })
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS))
                    assertFailsWith<TimeoutException> { result.get(200, TimeUnit.MILLISECONDS) }
                } finally {
                    heldLock.release()
                }
                assertEquals("1.9.11", result.get(5, TimeUnit.SECONDS).readText())
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `replaces an invalid cached binary with a verified download`() {
        val cli = File(cache, "1.9.11/macos/skip")
        cli.parentFile.mkdirs()
        cli.writeText("1.9.10")
        cli.setExecutable(true)
        val installed = SkipCliInstaller.install("1.9.11", cache, "macos", false, { it.readText() }) { _, destination ->
            archive(destination)
        }
        assertEquals(cli, installed)
        assertEquals("1.9.11", cli.readText())
    }

    @Test
    fun `selects release platform and rejects unsupported hosts`() {
        assertEquals("macos", SkipCliInstaller.platform("Mac OS X", "aarch64"))
        assertEquals("x86_64-swift-linux-musl", SkipCliInstaller.platform("Linux", "amd64"))
        assertEquals("aarch64-swift-linux-musl", SkipCliInstaller.platform("Linux", "aarch64"))
        assertFailsWith<GradleException> { SkipCliInstaller.platform("Windows", "amd64") }
    }
}
