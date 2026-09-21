package com.theleftbit.skipspm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkipVersionCheckTest {

    // Verbatim shape of polymarket-shared's declaration.
    private val exactManifest = """
        .addingSkipDependencies([
          .package(url: "https://source.skip.tools/skip.git", exact: "1.9.3"),
          .package(url: "https://source.skip.tools/skip-fuse.git", exact: "1.0.2"),
        ])
    """.trimIndent()

    private val resolvedWithSkipPin = """
        {
          "pins" : [
            {
              "identity" : "skip-bridge",
              "kind" : "remoteSourceControl",
              "location" : "https://source.skip.tools/skip-bridge.git",
              "state" : { "revision" : "abc", "version" : "0.17.2" }
            },
            {
              "identity" : "skip",
              "kind" : "remoteSourceControl",
              "location" : "https://source.skip.tools/skip.git",
              "state" : { "revision" : "def", "version" : "1.9.3" }
            }
          ],
          "version" : 3
        }
    """.trimIndent()

    @Test
    fun `manifest exact pin is an exact requirement`() {
        val req = expectedSkipVersion(exactManifest, packageResolved = null)
        assertEquals(SkipVersionRequirement("1.9.3", exact = true), req)
    }

    @Test
    fun `manifest from pin is a minimum requirement`() {
        val req = expectedSkipVersion(
            """.package(url: "https://source.skip.tools/skip.git", from: "1.5.0"),""",
            packageResolved = null,
        )
        assertEquals(SkipVersionRequirement("1.5.0", exact = false), req)
    }

    @Test
    fun `manifest upToNextMajor pin is a minimum requirement`() {
        val req = expectedSkipVersion(
            """.package(url: "https://source.skip.tools/skip.git", .upToNextMajor(from: "1.5.13")),""",
            packageResolved = null,
        )
        assertEquals(SkipVersionRequirement("1.5.13", exact = false), req)
    }

    @Test
    fun `resolved pin wins over a manifest minimum`() {
        val req = expectedSkipVersion(
            """.package(url: "https://source.skip.tools/skip.git", from: "1.5.0"),""",
            resolvedWithSkipPin,
        )
        assertEquals(SkipVersionRequirement("1.9.3", exact = true), req)
    }

    @Test
    fun `resolved pin wins over a manifest exact requirement`() {
        assertEquals(SkipVersionRequirement("1.9.3", exact = true), expectedSkipVersion(
            """.package(url: "https://github.com/skiptools/skip.git", exact: "1.9.11"),""",
            resolvedWithSkipPin,
        ))
    }

    @Test
    fun `resolved pin is used when manifest has no version requirement`() {
        assertEquals(SkipVersionRequirement("1.9.3", exact = true),
            expectedSkipVersion("// swift-tools-version:5.9", resolvedWithSkipPin))
    }

    @Test
    fun `sibling skip-dash packages never match`() {
        // Only skip.git / identity "skip" counts; skip-fuse, skip-bridge, … are separate packages.
        val req = expectedSkipVersion(
            """.package(url: "https://source.skip.tools/skip-fuse.git", exact: "1.0.2"),""",
            """{"pins":[{"identity":"skip-bridge","state":{"version":"0.17.2"}}]}""",
        )
        assertNull(req)
    }

    @Test
    fun `branch pin never borrows the version of the following package`() {
        assertNull(expectedSkipVersion(null, """
            {"pins":[
              {"identity":"skip","state":{"branch":"main","revision":"abc"}},
              {"identity":"skip-unit","state":{"version":"1.7.2"}}
            ]}
        """))
    }

    @Test
    fun `resolved pin parsing does not depend on JSON key order`() {
        assertEquals(SkipVersionRequirement("1.9.11", true), expectedSkipVersion(null,
            """{"pins":[{"state":{"version":"1.9.11"},"identity":"skip"}]}"""))
    }

    @Test
    fun `no declaration anywhere means no check`() {
        assertNull(expectedSkipVersion("// swift-tools-version:5.9", packageResolved = null))
        assertNull(expectedSkipVersion(null, null))
    }

    @Test
    fun `cli version parses from skip version output`() {
        assertEquals("1.9.4", parseSkipCliVersion("Skip version 1.9.4"))
        assertNull(parseSkipCliVersion("command not found"))
    }

    @Test
    fun `version parsing ignores numbers in warnings`() {
        assertEquals("1.9.11", parseSkipCliVersion("Warning: Swift 6.4.0 detected\nSkip version 1.9.11\n"))
        assertNull(parseSkipCliVersion("Error: requires Swift 6.4.0"))
    }

    @Test
    fun `prerelease cli is not mistaken for a stable release`() {
        val prerelease = parseSkipCliVersion("Skip version 1.9.11-beta.1")!!
        assertEquals("1.9.11-beta.1", prerelease)
        assertNotNull(SkipVersionRequirement("1.9.11", exact = true).mismatchWith(prerelease))
        assertNotNull(SkipVersionRequirement("1.9.11", exact = false).mismatchWith(prerelease))
    }

    @Test
    fun `exact requirement flags any drift, either direction`() {
        val req = SkipVersionRequirement("1.9.3", exact = true)
        assertNotNull(req.mismatchWith("1.9.4"))
        assertNotNull(req.mismatchWith("1.8.0"))
        assertNull(req.mismatchWith("1.9.3"))
    }

    @Test
    fun `minimum requirement only flags an older cli`() {
        val req = SkipVersionRequirement("1.9.3", exact = false)
        assertNull(req.mismatchWith("1.9.3"))
        assertNull(req.mismatchWith("1.10.0"))
        assertTrue(req.mismatchWith("1.9.2")!!.contains("at least"))
    }

    @Test
    fun `version comparison is numeric, not lexicographic`() {
        assertTrue(compareDottedVersions("1.10.0", "1.9.9") > 0)
        assertEquals(0, compareDottedVersions("1.9", "1.9.0"))
        assertTrue(compareDottedVersions("0.17.2", "0.17.10") < 0)
    }
    @Test
    fun `stable release sorts after prereleases and build metadata does not affect precedence`() {
        assertTrue(compareDottedVersions("1.9.11", "1.9.11-beta.1") > 0)
        assertTrue(compareDottedVersions("1.9.11-beta.10", "1.9.11-beta.2") > 0)
        assertTrue(compareDottedVersions("1.9.11-rc.1", "1.9.11-beta.10") > 0)
        assertTrue(compareDottedVersions("1.9.11-beta.1", "1.9.11-beta") > 0)
        assertTrue(compareDottedVersions("1.9.11-alpha", "1.9.11-1") > 0)
        assertTrue(compareDottedVersions("1.9.11--1", "1.9.11-1") > 0)
        assertEquals(0, compareDottedVersions("1.9.11+build.2", "1.9.11+build.1"))
        assertNotNull(SkipVersionRequirement("1.9.11-beta.2", false).mismatchWith("1.9.11-beta.1"))
    }

}
