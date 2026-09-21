package com.theleftbit.skipspm

import org.gradle.api.GradleException
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkipCliInstallerTest {
    @TempDir
    lateinit var prefix: File
    private val commands = mutableListOf<List<String>>()
    private var latest = "1.9.11"
    private var installedVersion = "1.9.11"
    private var installFailure = false

    private fun cli(version: String, cask: Boolean = false): File {
        val path = if (cask) "Caskroom/skip/$version/skip.artifactbundle/bin/skip" else "Cellar/skip/$version/bin/skip"
        return File(prefix, path).apply {
            parentFile.mkdirs()
            writeText(version)
            setExecutable(true)
        }
    }

    private fun brew(args: List<String>): String {
        commands += args
        return when (args.first()) {
            "--cellar" -> File(prefix, "Cellar").absolutePath
            "--prefix" -> prefix.absolutePath
            "update" -> ""
            "info" -> {
                assertEquals(listOf("info", "--json=v2", "--formula", "skip"), args)
                """{"formulae":[{"versions":{"stable":"$latest"}}]}"""
            }
            "upgrade", "install" -> {
                assertEquals(listOf(args.first(), "--formula", "skip"), args)
                if (installFailure) throw GradleException("brew failed")
                cli(installedVersion)
                ""
            }
            else -> error("Unexpected brew command: $args")
        }
    }

    private fun select(
        version: String,
        exact: Boolean = true,
        current: String = "1.9.3",
        offline: Boolean = false,
    ) = SkipCliInstaller.selectOrInstall(
        SkipVersionRequirement(version, exact), current,
        offline, { it.readText() }, ::brew,
    )

    private fun assertReadOnly() {
        assertEquals(listOf(listOf("--cellar"), listOf("--prefix")), commands)
    }

    @Test
    fun `older installed exact version is selected without updating Homebrew`() {
        val old = cli("1.9.3")
        cli("1.9.11")
        assertEquals(old, select("1.9.3", current = "1.9.11"))
        assertReadOnly()
    }

    @Test
    fun `legacy cask can be reused offline`() {
        val old = cli("1.9.3", cask = true)
        assertEquals(old, select("1.9.3", current = "1.9.11", offline = true))
        assertReadOnly()
    }

    @Test
    fun `newest compatible installed version satisfies a minimum offline`() {
        cli("1.9.8")
        val newest = cli("1.9.11")
        assertEquals(newest, select("1.9.7", exact = false, offline = true))
        assertReadOnly()
    }

    @Test
    fun `missing old version is never downloaded`() {
        assertFailsWith<GradleException> { select("1.9.3", current = "1.9.11") }
        assertReadOnly()
        assertFalse(File(prefix, "Cellar").exists())
    }

    @Test
    fun `newest installed version prevents downgrade even if active CLI is older`() {
        cli("1.9.11")
        assertFailsWith<GradleException> { select("1.9.8", current = "1.9.3") }
        assertReadOnly()
    }

    @Test
    fun `offline mismatch never updates or installs`() {
        val error = assertFailsWith<GradleException> { select("1.9.11", offline = true) }
        assertTrue(error.message!!.contains("without --offline"))
        assertReadOnly()
    }

    @Test
    fun `upgrades to latest compatible formula and keeps the old executable`() {
        val old = cli("1.9.3")
        assertEquals("1.9.11", select("1.9.7", exact = false).readText())
        assertEquals(listOf("--cellar", "--prefix", "update", "info", "upgrade"), commands.map { it.first() })
        assertTrue(old.canExecute())
    }

    @Test
    fun `installs latest formula when the active CLI is not a formula`() {
        assertEquals("1.9.11", select("1.9.11").readText())
        assertEquals(listOf("install", "--formula", "skip"), commands.last())
    }

    @Test
    fun `missing historical exact release newer than active CLI is not installed either`() {
        assertFailsWith<GradleException> { select("1.9.8") }
        assertEquals("info", commands.last().first())
    }

    @Test
    fun `latest release below project minimum does not mutate installations`() {
        assertFailsWith<GradleException> { select("1.9.12", exact = false) }
        assertEquals("info", commands.last().first())
    }

    @Test
    fun `latest release older than active version is never installed`() {
        latest = "1.9.8"
        assertFailsWith<GradleException> { select("1.9.12", current = "1.9.11") }
        assertEquals("info", commands.last().first())
    }

    @Test
    fun `Homebrew error is propagated without selecting the incompatible CLI`() {
        installFailure = true
        val error = assertFailsWith<GradleException> { select("1.9.11") }
        assertEquals("brew failed", error.message)
    }

    @Test
    fun `installed binary must report a compatible version after upgrade`() {
        installedVersion = "1.9.3"
        assertFailsWith<GradleException> { select("1.9.11") }
        assertEquals("install", commands.last().first())
    }

    @Test
    fun `unusable installed executable is not selected`() {
        val broken = cli("1.9.11")
        broken.setExecutable(false)
        assertFailsWith<GradleException> { select("1.9.11", offline = true) }
        assertReadOnly()
    }

    @Test
    fun `unrecognized Homebrew release cannot trigger installation`() {
        latest = "HEAD"
        assertFailsWith<GradleException> { select("1.9.11") }
        assertEquals("info", commands.last().first())
    }
    @Test
    fun `Homebrew prerelease is never automatically installed`() {
        latest = "1.9.12-beta.1"
        assertFailsWith<GradleException> { select("1.9.11", exact = false) }
        assertEquals("info", commands.last().first())
        assertFalse(File(prefix, "Cellar").exists())
    }

    @Test
    fun `manually installed prerelease may be reused offline`() {
        val beta = cli("1.9.12-beta.1")
        assertEquals(beta, select("1.9.12-beta.1", offline = true))
        assertReadOnly()
    }

    @Test
    fun `installed stable release wins over active beta with same core version`() {
        val stable = cli("1.9.11")
        assertEquals(stable, select("1.9.11", exact = false, current = "1.9.11-beta.1"))
        assertReadOnly()
    }

    @Test
    fun `manual beta does not prevent installing its newer stable release`() {
        assertEquals("1.9.11", select("1.9.11", current = "1.9.11-beta.1").readText())
        assertEquals(listOf("install", "--formula", "skip"), commands.last())
    }

}
