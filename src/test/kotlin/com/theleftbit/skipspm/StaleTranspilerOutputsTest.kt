package com.theleftbit.skipspm

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaleTranspilerOutputsClassifierTest {

    @Test
    fun `missing vendored manifest inside skipstone outputs is stale`() {
        // Verbatim shape of the failure seen after an external `git restore Package.resolved`.
        val output = """
            [✗] Assemble frameworks for USLive
            error: 'skip-keychain': the package manifest at '/repo/polymarket-shared/.build/plugins/outputs/polymarket-shared/USLive/destination/skipstone/USLive/src/main/swift/Packages/skip-keychain/Package.swift' cannot be accessed (/repo/…/Packages/skip-keychain/Package.swift doesn't exist in file system)
        """.trimIndent()
        assertTrue(looksLikeStaleTranspilerOutputs(output))
    }

    @Test
    fun `missing manifest outside the transpiler outputs is not stale`() {
        // A genuinely broken user package must fail fast, not burn a clean re-export.
        val output =
            "error: 'my-lib': the package manifest at '/Users/dev/my-lib/Package.swift' cannot be accessed (doesn't exist)"
        assertFalse(looksLikeStaleTranspilerOutputs(output))
    }

    @Test
    fun `unresolved bridge runtime symbols are stale`() {
        val k2 = "e: file:///…/skipstone/AppData/src/main/kotlin/AppData.kt:12:5 Unresolved reference 'SwiftPeerBridged'."
        val k1 = "e: /…/AppData.kt: (12, 5): Unresolved reference: SwiftObjectPointer"
        assertTrue(looksLikeStaleTranspilerOutputs(k2))
        assertTrue(looksLikeStaleTranspilerOutputs(k1))
    }

    @Test
    fun `unresolved user symbol is not stale`() {
        assertFalse(looksLikeStaleTranspilerOutputs("e: AppData.kt: Unresolved reference 'myTypoedFunction'."))
    }

    @Test
    fun `swift compile error is not stale`() {
        assertFalse(
            looksLikeStaleTranspilerOutputs(
                "error: cannot find 'frobnicate' in scope\n[✗] Assemble frameworks for USLive",
            ),
        )
    }

    @Test
    fun `transient fetch error is not stale`() {
        assertFalse(looksLikeStaleTranspilerOutputs("error: Couldn’t fetch updates from remote repositories"))
    }
}

class AarHasCompiledClassesTest {

    private val tempDir = createTempDirectory("skipspm-test").toFile()

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `aar with a compiled class is not a husk`() {
        val aar = writeAar("AppData-debug.aar", classesJarEntries = listOf("com/x/Foo.class"))
        assertTrue(aarHasCompiledClasses(aar))
    }

    @Test
    fun `aar whose classes jar is an empty zip is a husk`() {
        val aar = writeAar("USLive-debug.aar", classesJarEntries = emptyList())
        assertFalse(aarHasCompiledClasses(aar))
    }

    @Test
    fun `aar without a classes jar is a husk`() {
        val aar = writeAar("Empty-debug.aar", classesJarEntries = null)
        assertFalse(aarHasCompiledClasses(aar))
    }

    @Test
    fun `classes jar with only resources is a husk`() {
        val aar = writeAar("Res-debug.aar", classesJarEntries = listOf("META-INF/MANIFEST.MF", "res.txt"))
        assertFalse(aarHasCompiledClasses(aar))
    }

    /** Builds `<name>` in [tempDir]: an AAR zip whose classes.jar holds [classesJarEntries] (null = no classes.jar). */
    private fun writeAar(name: String, classesJarEntries: List<String>?): File {
        val aar = File(tempDir, name)
        ZipOutputStream(aar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("<manifest package=\"com.x\"/>".toByteArray())
            zip.closeEntry()
            if (classesJarEntries != null) {
                val jarBytes = ByteArrayOutputStream().also { buf ->
                    ZipOutputStream(buf).use { jar ->
                        classesJarEntries.forEach { entry ->
                            jar.putNextEntry(ZipEntry(entry))
                            jar.write(byteArrayOf(1, 2, 3))
                            jar.closeEntry()
                        }
                    }
                }.toByteArray()
                zip.putNextEntry(ZipEntry("classes.jar"))
                zip.write(jarBytes)
                zip.closeEntry()
            }
        }
        return aar
    }
}
