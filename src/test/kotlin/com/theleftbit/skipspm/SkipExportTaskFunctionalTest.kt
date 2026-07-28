package com.theleftbit.skipspm

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives a real Gradle build of [SkipExportTask] with a *fake* `skip` executable (injected via the
 * task's `SKIP_PATH` override), so the stale-outputs self-heal loop is exercised end to end —
 * failure classification, `.build/plugins/outputs` cleanup, retry, and the give-up path — without
 * a Swift toolchain.
 */
class SkipExportTaskFunctionalTest {

    private val projectDir = createTempDirectory("skipspm-functional").toFile()
    private val pkgDir = File(projectDir, "pkg")
    private val transpilerOutputs = File(pkgDir, TRANSPILER_OUTPUTS_PATH)
    private val fixturesDir = File(projectDir, "fixtures")
    private val invocationMarker = File(projectDir, "skip-invoked-once")

    /** Verbatim shape of the SwiftPM failure seen after the transpiler outputs went stale. */
    private val staleManifestError =
        "error: 'skip-keychain': the package manifest at " +
            "'/repo/.build/plugins/outputs/pkg/USLive/destination/skipstone/USLive/src/main/swift/" +
            "Packages/skip-keychain/Package.swift' cannot be accessed (doesn't exist in file system)"

    @AfterTest
    fun tearDown() {
        projectDir.deleteRecursively()
    }

    @Test
    fun `self-heals stale transpiler outputs and re-exports`() {
        // Fake skip: fails with the stale-manifest signature on the first call, succeeds on the second.
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val fakeSkip = writeFakeSkip(
            """
            if [ ! -f "${invocationMarker.absolutePath}" ]; then
              touch "${invocationMarker.absolutePath}"
              echo "$staleManifestError" >&2
              exit 1
            fi
            cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
            """,
        )
        val staleJunk = File(transpilerOutputs, "pkg/USLive/destination/skipstone/junk.txt")
        staleJunk.parentFile.mkdirs()
        staleJunk.writeText("stale")

        val result = runner(fakeSkip).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertContains(result.output, "stale transpiler outputs")
        assertFalse(staleJunk.exists(), "self-heal should have deleted the transpiler outputs")
        val aar = File(projectDir, "lib/debug/TestModule-debug.aar")
        assertTrue(aar.isFile, "the retried export should have produced the AAR")
        assertContains(readManifest(aar), "package=\"com.test.shared.testmodule\"")
    }

    @Test
    fun `husk AAR triggers the same self-heal`() {
        // Fake skip: succeeds both times, but the first export packages a husk (no compiled classes).
        val huskDir = File(projectDir, "fixtures-husk").apply { mkdirs() }
        writeAar(huskDir, "TestModule-debug.aar", emptyList())
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val fakeSkip = writeFakeSkip(
            """
            if [ ! -f "${invocationMarker.absolutePath}" ]; then
              touch "${invocationMarker.absolutePath}"
              cp "${huskDir.absolutePath}"/*.aar "${'$'}out"/
              exit 0
            fi
            cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
            """,
        )
        transpilerOutputs.mkdirs()

        val result = runner(fakeSkip).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertContains(result.output, "husk AARs")
        assertTrue(aarHasCompiledClasses(File(projectDir, "lib/debug/TestModule-debug.aar")))
    }

    @Test
    fun `gives up after one self-heal attempt`() {
        val fakeSkip = writeFakeSkip(
            """
            echo "$staleManifestError" >&2
            exit 1
            """,
        )
        transpilerOutputs.mkdirs()

        val result = runner(fakeSkip).buildAndFail()

        assertContains(result.output, "a clean re-export did not recover")
        assertContains(result.output, "cleanSharedBuild")
    }

    private fun runner(fakeSkip: File): GradleRunner {
        File(pkgDir, "Sources").mkdirs()
        File(pkgDir, "Sources/placeholder.swift").writeText("// swift source")
        File(pkgDir, "Package.swift").writeText("// swift-tools-version:5.9")
        File(projectDir, "settings.gradle.kts").writeText("rootProject.name = \"export-test\"")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins { id("com.theleftbit.skipspm") apply false }

            tasks.register("exportTest", com.theleftbit.skipspm.SkipExportTask::class.java) {
                packageDir.set(layout.projectDirectory.dir("pkg"))
                sources.set(layout.projectDirectory.dir("pkg/Sources"))
                manifests.from(layout.projectDirectory.file("pkg/Package.swift"))
                module.set("TestModule")
                buildMode.set("debug")
                abis.set(listOf("arm64-v8a"))
                namespacePrefix.set("com.test.shared")
                outputDir.set(layout.projectDirectory.dir("lib/debug"))
                pruneStaleTransforms.set(false)
            }
            """.trimIndent(),
        )
        return GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("exportTest")
            .withEnvironment(System.getenv() + ("SKIP_PATH" to fakeSkip.absolutePath))
    }

    /** A fake `skip` CLI: parses `-d <out>` like the real one, then runs [body]. */
    private fun writeFakeSkip(body: String): File {
        val script = File(projectDir, "fake-skip")
        script.writeText(
            buildString {
                appendLine("#!/bin/bash")
                appendLine("out=\"\"")
                appendLine("prev=\"\"")
                appendLine("for a in \"\$@\"; do")
                appendLine("  if [ \"\$prev\" = \"-d\" ]; then out=\"\$a\"; fi")
                appendLine("  prev=\"\$a\"")
                appendLine("done")
                appendLine(body.trimIndent())
            },
        )
        script.setExecutable(true)
        return script
    }

    private fun readManifest(aar: File): String =
        ZipFile(aar).use { zip ->
            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes().decodeToString()
        }

    /** Writes `<name>` into [dir]: an AAR whose classes.jar holds [classEntries]. */
    private fun writeAar(dir: File, name: String, classEntries: List<String>) {
        dir.mkdirs()
        val aar = File(dir, name)
        ZipOutputStream(aar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("<manifest package=\"skip.placeholder\"/>".toByteArray())
            zip.closeEntry()
            val jarBytes = ByteArrayOutputStream().also { buf ->
                ZipOutputStream(buf).use { jar ->
                    classEntries.forEach { entry ->
                        jar.putNextEntry(ZipEntry(entry))
                        jar.write(byteArrayOf(1, 2, 3))
                        jar.closeEntry()
                    }
                    if (classEntries.isEmpty()) {
                        // An empty ZipOutputStream throws on close; give husks a resource-only entry.
                        jar.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                        jar.write(byteArrayOf(1))
                        jar.closeEntry()
                    }
                }
            }.toByteArray()
            zip.putNextEntry(ZipEntry("classes.jar"))
            zip.write(jarBytes)
            zip.closeEntry()
        }
    }
}
