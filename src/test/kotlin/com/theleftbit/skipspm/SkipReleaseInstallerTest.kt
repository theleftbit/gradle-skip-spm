package com.theleftbit.skipspm

import org.gradle.api.GradleException
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkipReleaseInstallerTest {
    @TempDir lateinit var root: File
    private val requests = mutableListOf<String>()

    private fun archive(version: String = "1.9.19", path: String = "skip.artifactbundle/bin/skip"): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { zip ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(version.toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

    private fun installer(bytes: ByteArray = archive(), digest: String? = null) = SkipReleaseInstaller(
        root, { it.takeIf(File::isFile)?.readText() }, "macos",
    ) { url, target ->
        requests += url
        if (url.endsWith(".zip")) target.writeBytes(bytes)
        else {
            val hash = digest ?: MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            target.writeText("""{"assets":[{"name":"skip-macos.zip","digest":"sha256:$hash"}]}""")
        }
    }

    @Test
    fun `downloads exact release verifies digest and reuses it offline`() {
        val cli = installer().select("1.9.19", false)!!
        assertTrue(cli.canExecute())
        assertEquals("1.9.19", cli.readText())
        assertEquals(2, requests.size)
        assertEquals(cli, installer().select("1.9.19", true))
        assertEquals(2, requests.size)
        assertNull(installer().select("1.9.20", true))
    }

    @Test
    fun `different project versions coexist`() {
        val first = installer().select("1.9.19", false)!!
        val second = installer(archive("1.9.20")).select("1.9.20", false)!!
        assertEquals("1.9.19", first.readText())
        assertEquals("1.9.20", second.readText())
    }

    @Test
    fun `checksum mismatch never publishes a CLI`() {
        assertFailsWith<GradleException> { installer(digest = "0".repeat(64)).select("1.9.19", false) }
        assertFalse(File(root, "macos/1.9.19").exists())
        assertEquals(listOf("1.9.19.lock"), File(root, "macos").list()!!.toList())
    }

    @Test
    fun `wrong binary version is rejected`() {
        assertFailsWith<GradleException> { installer(archive("1.9.20")).select("1.9.19", false) }
        assertFalse(File(root, "macos/1.9.19").exists())
    }

    @Test
    fun `failed download is retried on the next call without a partial cache entry`() {
        val broken = SkipReleaseInstaller(root, { null }, "macos") { _, _ -> error("network unavailable") }
        assertFailsWith<GradleException> { broken.select("1.9.19", false) }
        assertNull(installer().select("1.9.19", true))
        assertEquals("1.9.19", installer().select("1.9.19", false)!!.readText())
    }

    @Test
    fun `archive entries cannot escape staging`() {
        assertFailsWith<GradleException> { installer(archive(path = "../escaped")).select("1.9.19", false) }
        assertFalse(File(root, "macos/escaped").exists())
    }

    @Test
    fun `offline cache miss and prereleases never download`() {
        assertNull(installer().select("1.9.19", true))
        assertNull(installer().select("1.9.20-beta.1", false))
        assertTrue(requests.isEmpty())
    }
}
