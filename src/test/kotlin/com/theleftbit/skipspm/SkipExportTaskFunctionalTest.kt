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

        val gradleRunner = runner(fakeSkip)
        // skipstone's outputs symlink back into the package's real Sources; the self-heal must
        // delete the link itself, never the sources behind it.
        val sourcesLink = File(staleJunk.parentFile, "swift-sources")
        java.nio.file.Files.createSymbolicLink(sourcesLink.toPath(), File(pkgDir, "Sources").toPath())
        val realSource = File(pkgDir, "Sources/placeholder.swift")

        val result = gradleRunner.build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertContains(result.output, "stale transpiler outputs")
        assertFalse(staleJunk.exists(), "self-heal should have deleted the transpiler outputs")
        assertTrue(realSource.isFile, "self-heal must not follow symlinks into the real Sources")
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
        assertTrue(aarHasCompiledOutput(File(projectDir, "lib/debug/TestModule-debug.aar")))
    }

    @Test
    fun `metadata-only AAR is accepted without a self-heal`() {
        // Regression (0.5.0): SkipSwiftUI's classes.jar holds Kotlin metadata and zero .class
        // entries; the husk check misread it, self-healed, and failed again on the same output.
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        writeAar(fixturesDir, "SkipSwiftUI-debug.aar", listOf("META-INF/SkipSwiftUI.kotlin_module"))
        val fakeSkip = writeFakeSkip(
            """
            if [ -f "${invocationMarker.absolutePath}" ]; then
              echo "fake skip invoked twice: a metadata-only AAR must not trigger a self-heal" >&2
              exit 1
            fi
            touch "${invocationMarker.absolutePath}"
            cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
            """,
        )
        transpilerOutputs.mkdirs()

        val result = runner(fakeSkip).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertFalse(result.output.contains("husk AARs"))
        assertTrue(File(projectDir, "lib/debug/SkipSwiftUI-debug.aar").isFile)
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

    @Test
    fun `leads skip children's PATH with the configured gradle and disables their build cache`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val envDump = File(projectDir, "skip-env")
        val fakeSkip = writeFakeSkip(
            """
            printenv PATH > "${envDump.absolutePath}"
            printenv GRADLE_OPTS >> "${envDump.absolutePath}" || true
            cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
            """,
        )

        val result = runner(
            fakeSkip,
            extraTaskConfig = """gradleInstallBinDir.set("/fake/gradle-dist/bin")""",
            extraEnv = mapOf("GRADLE_OPTS" to "-Xmx1g"),
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        val (path, opts) = envDump.readLines().let { it[0] to it.getOrElse(1) { "" } }
        assertTrue(
            path.startsWith("/fake/gradle-dist/bin:"),
            "children's PATH should lead with the configured gradle bin dir, was: $path",
        )
        assertContains(opts, "-Xmx1g", message = "inherited GRADLE_OPTS must be preserved")
        assertContains(opts, "-Dorg.gradle.caching=false")
    }

    @Test
    fun `childGradleBuildCache=true leaves the children's build cache alone`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val envDump = File(projectDir, "skip-env")
        val fakeSkip = writeFakeSkip(
            """
            printenv GRADLE_OPTS > "${envDump.absolutePath}" || true
            cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
            """,
        )

        val result = runner(
            fakeSkip,
            extraTaskConfig = """childGradleBuildCache.set(true)""",
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        val opts = envDump.readLines().firstOrNull().orEmpty()
        assertFalse(
            opts.contains("org.gradle.caching=false"),
            "opt-in must not inject the caching override, was: $opts",
        )
    }

    @Test
    fun `warn mode preserves diagnostics when the skip CLI drifts from the manifest pin`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val fakeSkip = writeFakeSkip("""cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/""")

        // Fake CLI reports 1.9.3; the manifest pins 9.9.9 exactly.
        val result = runner(
            fakeSkip,
            extraTaskConfig = """skipVersionCheck.set("warn")""",
            packageSwift = """.package(url: "https://source.skip.tools/skip.git", exact: "9.9.9"),""",
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertContains(result.output, "the skip CLI is 1.9.3 but the package pins skip 9.9.9")
    }

    @Test
    fun `fails on drift when skipVersionCheck is fail`() {
        val fakeSkip = writeFakeSkip("exit 0")

        val result = runner(
            fakeSkip,
            packageSwift = """.package(url: "https://source.skip.tools/skip.git", exact: "9.9.9"),""",
            extraTaskConfig = """skipVersionCheck.set("fail")""",
        ).buildAndFail()

        assertContains(result.output, "the skip CLI is 1.9.3 but the package pins skip 9.9.9")
    }

    @Test
    fun `matching cli version stays silent`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val fakeSkip = writeFakeSkip("""cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/""")

        val result = runner(
            fakeSkip,
            packageSwift = """.package(url: "https://source.skip.tools/skip.git", exact: "1.9.3"),""",
            extraTaskConfig = """skipVersionCheck.set("fail")""",
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertFalse(result.output.contains("skip CLI"), "no drift message expected")
    }

    @Test
    fun `default mode exports with an older installed CLI without changing global CLI`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val oldCli = writeFakeSkip("exit 81")
        val cellar = File(projectDir, "brew/Cellar/skip")
        val installedCli = File(cellar, "1.9.2/bin/skip")
        installedCli.parentFile.mkdirs()
        installedCli.writeText(oldCli.readText().replace("1.9.3", "1.9.2").replace("exit 81", "cp \"${fixturesDir.absolutePath}\"/*.aar \"\$out\"/"))
        installedCli.setExecutable(true)
        val result = runner(
            oldCli,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", exact: "1.9.2"),""",
            extraTaskConfig = """
                offline.set(true)
            """,
        ).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertContains(result.output, "selecting Skip CLI 1.9.2")
        assertContains(oldCli.readText(), "1.9.3")
    }

    @Test
    fun `default mode upgrades Homebrew and exports with the newly installed CLI`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val active = writeFakeSkip("exit 81")
        val previous = File(projectDir, "brew/Cellar/skip/1.9.3/bin/skip")
        previous.parentFile.mkdirs()
        active.copyTo(previous)
        previous.setExecutable(true)
        File(projectDir, "new-skip").apply {
            writeText(active.readText().replace("1.9.3", "9.9.9").replace("exit 81", """
                printenv PATH > "${File(projectDir, "child-path").absolutePath}"
                cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
            """.trimIndent()))
            setExecutable(true)
        }
        val result = runner(active,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", from: "1.9.7"),""",
        ).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertEquals(listOf("--cellar", "--prefix", "update", "info --json=v2 --formula skip", "upgrade --formula skip"),
            File(projectDir, "brew-calls").readLines())
        assertContains(previous.readText(), "Skip version 1.9.3")
        val selectedBin = File(projectDir, "brew/Cellar/skip/9.9.9/bin").canonicalFile
        assertEquals(selectedBin, File(File(projectDir, "child-path").readText().substringBefore(':')).canonicalFile)
    }

    @Test
    fun `missing older exact CLI fails without updating Homebrew or exporting`() {
        val active = writeFakeSkip("touch \"${invocationMarker.absolutePath}\"; exit 81")
        val result = runner(active,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", exact: "1.9.2"),""",
        ).buildAndFail()
        assertContains(result.output, "Older versions may only be reused when already installed")
        assertEquals(listOf("--cellar", "--prefix"), File(projectDir, "brew-calls").readLines())
        assertFalse(invocationMarker.exists())
    }

    @Test
    fun `default mode keeps compatible CLI and original child PATH`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val pathDump = File(projectDir, "child-path")
        val fakeSkip = writeFakeSkip("""
            printenv PATH > "${pathDump.absolutePath}"
            cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
        """)
        val result = runner(fakeSkip,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", exact: "1.9.3"),""",
            extraTaskConfig = """
                offline.set(true)
                gradleInstallBinDir.set("/fake/gradle/bin")
            """,
        ).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertFalse(File(projectDir, "brew-calls").exists())
        assertEquals("/fake/gradle/bin:/opt/homebrew/bin:/usr/local/bin:${System.getenv("PATH")}\n", pathDump.readText())
    }

    @Test
    fun `off mode neither checks nor replaces a mismatched CLI`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val fakeSkip = writeFakeSkip("""cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/""")
        fakeSkip.writeText(fakeSkip.readText().replace("echo \"Skip version 1.9.3\"", "touch \"${invocationMarker.absolutePath}\"; echo \"Skip version 1.9.3\""))
        val result = runner(fakeSkip,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", exact: "9.9.9"),""",
            extraTaskConfig = """skipVersionCheck.set("off")""",
        ).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertFalse(invocationMarker.exists())
        assertFalse(result.output.contains("selecting Skip CLI"))
    }

    @Test
    fun `unknown CLI version preserves the original export without installing`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val fakeSkip = writeFakeSkip("""cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/""")
        fakeSkip.writeText(fakeSkip.readText().replace("Skip version 1.9.3", "Custom CLI using Swift 6.4.0"))
        val result = runner(fakeSkip,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", exact: "9.9.9"),""",
            extraTaskConfig = """
                offline.set(true)
            """,
        ).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertFalse(File(projectDir, "brew-calls").exists())
        assertFalse(result.output.contains("selecting Skip CLI"))
    }

    @Test
    fun `missing CLI still reports the original process failure`() {
        val result = runner(File(projectDir, "missing-skip"),
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", exact: "9.9.9"),""",
            extraTaskConfig = """
                offline.set(true)
            """,
        ).buildAndFail()
        assertContains(result.output, "missing-skip")
        assertFalse(File(projectDir, "brew-calls").exists())
        assertFalse(result.output.contains("selecting Skip CLI"))
    }

    @Test
    fun `credential failures with an exact requirement do not install or retry the CLI`() {
        val fakeSkip = writeFakeSkip("""
            echo export >> "${invocationMarker.absolutePath}"
            echo "Failed to find credentials for https://github.com in keychain: status -25308" >&2
            exit 1
        """)
        val result = runner(fakeSkip,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", exact: "1.9.3"),""",
        ).buildAndFail()
        assertContains(result.output, "status -25308")
        assertEquals(listOf("export"), invocationMarker.readLines())
        assertFalse(File(projectDir, "brew-calls").exists())
    }

    @Test
    fun `offline mismatch fails before executing the wrong CLI`() {
        val fakeSkip = writeFakeSkip("""touch "${invocationMarker.absolutePath}"; exit 1""")
        val result = runner(fakeSkip,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", exact: "9.9.9"),""",
        ).withArguments("exportTest", "--offline").buildAndFail()
        assertContains(result.output, "Run once without --offline")
        assertFalse(invocationMarker.exists())
    }

    @Test
    fun `managed CLI respects resolved pin restores lockfile and reuses configuration cache`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val fakeSkip = writeFakeSkip("exit 81")
        val installedCli = File(projectDir, "brew/Cellar/skip/9.9.9/bin/skip")
        installedCli.parentFile.mkdirs()
        installedCli.writeText(fakeSkip.readText().replace("1.9.3", "9.9.9").replace("exit 81", """
            echo export >> "${invocationMarker.absolutePath}"
            echo changed > "${File(pkgDir, "Package.resolved").absolutePath}"
            printenv PATH > "${File(projectDir, "child-path").absolutePath}"
            cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
        """.trimIndent()))
        installedCli.setExecutable(true)
        val gradleRunner = runner(fakeSkip,
            packageSwift = "// swift-tools-version:5.9",
        ).withArguments("exportTest", "--offline", "--configuration-cache")
        val lockfile = File(pkgDir, "Package.resolved")
        val original = """{"pins":[{"identity":"skip","state":{"version":"9.9.9"}}]}"""
        lockfile.writeText(original)
        assertEquals(TaskOutcome.SUCCESS, gradleRunner.build().task(":exportTest")?.outcome)
        assertEquals(original, lockfile.readText())
        assertEquals(installedCli.parentFile.canonicalFile, File(File(projectDir, "child-path").readText().substringBefore(':')).canonicalFile)
        val unchanged = gradleRunner.build()
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":exportTest")?.outcome)
        assertContains(unchanged.output, "Reusing configuration cache")
        File(pkgDir, "Sources/placeholder.swift").appendText("\n// change")
        val rebuilt = gradleRunner.build()
        assertContains(rebuilt.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.SUCCESS, rebuilt.task(":exportTest")?.outcome)
        assertEquals(listOf("export", "export"), invocationMarker.readLines())
        assertEquals(original, lockfile.readText())
    }

    @Test
    fun `transient fetch failure still retries with the selected CLI`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val fakeSkip = writeFakeSkip("""
            if [ ! -f "${invocationMarker.absolutePath}" ]; then
              touch "${invocationMarker.absolutePath}"
              echo "could not resolve host" >&2
              exit 1
            fi
            cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
        """)
        val result = runner(fakeSkip,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", exact: "1.9.3"),""",
        ).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertContains(result.output, "retrying in 10s")
        assertFalse(result.output.contains("selecting Skip CLI"))
    }

    @Test
    fun `resolved pin is enforced even when the active CLI satisfies the manifest minimum`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val fakeSkip = writeFakeSkip("""cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/""")
        val gradleRunner = runner(fakeSkip,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", from: "1.9.0"),""",
        ).withArguments("exportTest", "--offline")
        File(pkgDir, "Package.resolved").writeText("""{"pins":[{"identity":"skip","state":{"version":"9.9.9"}}]}""")
        val result = gradleRunner.buildAndFail()
        assertContains(result.output, "no compatible Skip CLI is installed")
        assertContains(result.output, "selecting Skip CLI")
        assertEquals(listOf("--cellar", "--prefix"), File(projectDir, "brew-calls").readLines())
    }

    @Test
    fun `compatible manifest minimum keeps active CLI without querying Homebrew`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val active = writeFakeSkip("""cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/""")
        val newest = File(projectDir, "brew/Cellar/skip/1.9.11/bin/skip")
        newest.parentFile.mkdirs()
        newest.writeText(active.readText().replace("1.9.3", "1.9.11"))
        newest.setExecutable(true)
        val result = runner(active,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", from: "1.9.0"),""",
        ).withArguments("exportTest", "--offline").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertFalse(File(projectDir, "brew-calls").exists())
    }

    @Test
    fun `newer resolved requirement retries once preserving original pin and child PATH`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val lockfile = File(pkgDir, "Package.resolved")
        val active = writeFakeSkip("""
            echo old >> "${invocationMarker.absolutePath}"
            echo '{"pins":[{"identity":"skip","state":{"version":"9.9.9"}}]}' > "${lockfile.absolutePath}"
            echo "export failed with this toolchain" >&2
            exit 1
        """)
        val oldKeg = File(projectDir, "brew/Cellar/skip/1.9.3/bin/skip")
        oldKeg.parentFile.mkdirs()
        active.copyTo(oldKeg)
        oldKeg.setExecutable(true)
        writeFakeSkip("""
            echo new >> "${invocationMarker.absolutePath}"
            cp "${lockfile.absolutePath}" "${File(projectDir, "observed-lockfile").absolutePath}"
            echo changed > "${lockfile.absolutePath}"
            printenv PATH > "${File(projectDir, "child-path").absolutePath}"
            cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
        """, version = "9.9.9", name = "new-skip")
        val gradleRunner = runner(active,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", from: "1.9.0"),""",
        ).withArguments("exportTest", "--configuration-cache")
        val original = """{"pins":[{"identity":"skip","state":{"version":"1.9.3"}}]}"""
        lockfile.writeText(original)
        val result = gradleRunner.build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertEquals(listOf("old", "new"), invocationMarker.readLines())
        assertEquals(original, lockfile.readText())
        assertEquals(original, File(projectDir, "observed-lockfile").readText())
        assertEquals(1, File(projectDir, "brew-calls").readLines().count { it == "update" })
        assertContains(File(projectDir, "brew-calls").readText(), "upgrade --formula skip")
        assertTrue(oldKeg.canExecute())
        assertEquals(File(projectDir, "brew/Cellar/skip/9.9.9/bin").canonicalFile,
            File(File(projectDir, "child-path").readText().substringBefore(':')).canonicalFile)
    }

    @Test
    fun `newer Skip requirement survives repeated stale-output failures`() {
        assertVersionRecoveryAfterCleanup("""echo "Unresolved reference 'SwiftPeerBridged'" >&2; exit 1""")
    }

    @Test
    fun `newer Skip requirement survives a clean retry failing before resolution`() {
        assertVersionRecoveryAfterCleanup(
            """echo "Unresolved reference 'SwiftPeerBridged'" >&2; exit 1""",
            """echo "compilation failed before dependency resolution" >&2; exit 1""",
        )
    }

    @Test
    fun `newer Skip requirement survives husk AAR validation and cleanup`() {
        val huskDir = File(projectDir, "husk-aars")
        writeAar(huskDir, "TestModule-debug.aar", emptyList())
        assertVersionRecoveryAfterCleanup("""cp "${huskDir.absolutePath}"/*.aar "${'$'}out"/; exit 0""")
    }

    @Test
    fun `newer Skip requirement recovers a stale failure with no outputs to clean`() {
        assertVersionRecoveryAfterCleanup(
            """echo "Unresolved reference 'SwiftPeerBridged'" >&2; exit 1""",
            hasOutputs = false,
        )
    }

    private fun assertVersionRecoveryAfterCleanup(
        initialFailure: String,
        cleanFailure: String = initialFailure,
        hasOutputs: Boolean = true,
    ) {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val lockfile = File(pkgDir, "Package.resolved")
        val active = writeFakeSkip("""
            if [ ! -f "${invocationMarker.absolutePath}" ]; then
                echo old >> "${invocationMarker.absolutePath}"
                echo '{"pins":[{"identity":"skip","state":{"version":"9.9.9"}}]}' > "${lockfile.absolutePath}"
                $initialFailure
            fi
            echo old >> "${invocationMarker.absolutePath}"
            $cleanFailure
        """)
        writeFakeSkip("""
            echo new >> "${invocationMarker.absolutePath}"
            cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
        """, version = "9.9.9", name = "new-skip")
        val build = runner(active,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", from: "1.9.0"),""",
        )
        val original = """{"pins":[{"identity":"skip","state":{"version":"1.9.3"}}]}"""
        lockfile.writeText(original)
        if (hasOutputs) transpilerOutputs.mkdirs()
        val result = build.build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertEquals(if (hasOutputs) listOf("old", "old", "new") else listOf("old", "new"),
            invocationMarker.readLines())
        assertEquals(original, lockfile.readText())
        assertFalse(transpilerOutputs.exists())
        assertEquals(1, File(projectDir, "brew-calls").readLines().count { it == "update" })
        assertTrue(aarHasCompiledOutput(File(projectDir, "lib/debug/TestModule-debug.aar")))
    }

    @Test
    fun `newer package requirement triggers one upgrade even if a compilation error persists`() {
        val active = writeFakeSkip("""
            echo old >> "${invocationMarker.absolutePath}"
            echo '{"pins":[{"identity":"skip","state":{"version":"9.9.9"}}]}' > "${File(pkgDir, "Package.resolved").absolutePath}"
            echo "error: cannot find 'myMissingVariable' in scope" >&2
            exit 1
        """)
        writeFakeSkip("""
            echo new >> "${invocationMarker.absolutePath}"
            echo "error: cannot find 'myMissingVariable' in scope" >&2
            exit 1
        """, version = "9.9.9", name = "new-skip")
        val result = runner(active,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", from: "1.9.0"),""",
        ).buildAndFail()
        assertContains(result.output, "cannot find 'myMissingVariable'")
        assertEquals(listOf("old", "new"), invocationMarker.readLines())
        assertEquals(1, File(projectDir, "brew-calls").readLines().count { it == "update" })
    }

    @Test
    fun `manifest fallback gets no further retries after upgrade even for transient errors`() {
        val active = writeFakeSkip("""
            echo old >> "${invocationMarker.absolutePath}"
            echo '{"pins":[{"identity":"skip","state":{"version":"9.9.9"}}]}' > "${File(pkgDir, "Package.resolved").absolutePath}"
            exit 1
        """)
        writeFakeSkip("""
            echo new >> "${invocationMarker.absolutePath}"
            echo "could not resolve host" >&2
            exit 1
        """, version = "9.9.9", name = "new-skip")
        val result = runner(active,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", from: "1.9.0"),""",
        ).buildAndFail()
        assertEquals(listOf("old", "new"), invocationMarker.readLines())
        assertEquals(1, File(projectDir, "brew-calls").readLines().count { it == "update" })
        assertFalse(result.output.contains("retrying in"))
    }

    @Test
    fun `manifest fallback does not retry when Homebrew has no newer release`() {
        val active = writeFakeSkip("""
            echo old >> "${invocationMarker.absolutePath}"
            echo '{"pins":[{"identity":"skip","state":{"version":"9.9.9"}}]}' > "${File(pkgDir, "Package.resolved").absolutePath}"
            exit 1
        """)
        val result = runner(active,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", from: "1.9.0"),""",
            extraEnv = mapOf("TEST_SKIP_LATEST" to "1.9.3"),
        ).buildAndFail()
        assertEquals(listOf("old"), invocationMarker.readLines())
        assertContains(result.output, "Skip CLI recovery failed")
        assertContains(result.output, "skip export failed (exit 1)")
        assertEquals("info --json=v2 --formula skip", File(projectDir, "brew-calls").readLines().last())
    }

    @Test
    fun `failed compatible CLI stays offline without attempting an upgrade`() {
        val active = writeFakeSkip("""
            echo old >> "${invocationMarker.absolutePath}"
            echo '{"pins":[{"identity":"skip","state":{"version":"9.9.9"}}]}' > "${File(pkgDir, "Package.resolved").absolutePath}"
            exit 1
        """)
        runner(active,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", from: "1.9.0"),""",
        ).withArguments("exportTest", "--offline").buildAndFail()
        assertEquals(listOf("old"), invocationMarker.readLines())
        assertFalse(File(projectDir, "brew-calls").exists())
    }

    @Test
    fun `legacy warn mode does not upgrade after a compatible CLI fails`() {
        val active = writeFakeSkip("""
            echo old >> "${invocationMarker.absolutePath}"
            echo '{"pins":[{"identity":"skip","state":{"version":"9.9.9"}}]}' > "${File(pkgDir, "Package.resolved").absolutePath}"
            exit 1
        """)
        runner(active,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", from: "1.9.0"),""",
            extraTaskConfig = """skipVersionCheck.set("warn")""",
        ).buildAndFail()
        assertEquals(listOf("old"), invocationMarker.readLines())
        assertFalse(File(projectDir, "brew-calls").exists())
    }

    @Test
    fun `new Skip pin from Android resolution reuses an installed CLI and keeps generated lockfile`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val lockfile = File(pkgDir, "Package.resolved")
        val active = writeFakeSkip("""
            echo old >> "${invocationMarker.absolutePath}"
            echo '{"pins":[{"identity":"skip","state":{"version":"9.9.9"}}]}' > "${lockfile.absolutePath}"
            exit 1
        """)
        val available = writeFakeSkip("""
            echo new >> "${invocationMarker.absolutePath}"
            cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
        """, version = "9.9.9", name = "new-skip")
        val installed = File(projectDir, "brew/Cellar/skip/9.9.9/bin/skip")
        installed.parentFile.mkdirs()
        available.copyTo(installed)
        installed.setExecutable(true)
        val result = runner(active,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", from: "1.9.0"),""",
        ).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertEquals(listOf("old", "new"), invocationMarker.readLines())
        assertEquals(SkipVersionRequirement("9.9.9", true), expectedSkipVersion(null, lockfile.readText()))
        assertEquals(listOf("--cellar", "--prefix"), File(projectDir, "brew-calls").readLines())
    }

    @Test
    fun `manifest minimum does not upgrade for unrelated export failures`() {
        val active = writeFakeSkip("""
            echo old >> "${invocationMarker.absolutePath}"
            echo "fatal: Authentication failed; compilation failed" >&2
            exit 1
        """)
        val result = runner(active,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", from: "1.9.0"),""",
        ).buildAndFail()
        assertContains(result.output, "Authentication failed")
        assertEquals(listOf("old"), invocationMarker.readLines())
        assertFalse(File(projectDir, "brew-calls").exists())
    }

    @Test
    fun `successful export keeps a lockfile that did not exist before`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val lockfile = File(pkgDir, "Package.resolved")
        val active = writeFakeSkip("""
            echo generated > "${lockfile.absolutePath}"
            cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
        """)
        val result = runner(active).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertEquals("generated\n", lockfile.readText())
    }

    @Test
    fun `failed export keeps a lockfile that did not exist before`() {
        val lockfile = File(pkgDir, "Package.resolved")
        val active = writeFakeSkip("""echo generated > "${lockfile.absolutePath}"; exit 1""")
        runner(active).buildAndFail()
        assertEquals("generated\n", lockfile.readText())
    }

    @Test
    fun `failed export leaves a deleted lockfile absent as before`() {
        val lockfile = File(pkgDir, "Package.resolved")
        val active = writeFakeSkip("""rm "${lockfile.absolutePath}"; exit 1""")
        val build = runner(active, extraTaskConfig = """skipVersionCheck.set("off")""")
        lockfile.writeText("original lockfile")
        build.buildAndFail()
        assertFalse(lockfile.exists())
    }

    @Test
    fun `resolved pin matches active CLI even when manifest minimum differs`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val active = writeFakeSkip("""cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/""")
        val build = runner(active,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", from: "9.9.9"),""",
        )
        File(pkgDir, "Package.resolved").writeText("""{"pins":[{"identity":"skip","state":{"version":"1.9.3"}}]}""")
        assertEquals(TaskOutcome.SUCCESS, build.build().task(":exportTest")?.outcome)
        assertFalse(File(projectDir, "brew-calls").exists())
    }

    @Test
    fun `installs the newer resolved target regardless of the manifest exact requirement`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val active = writeFakeSkip("""echo old >> "${invocationMarker.absolutePath}"; exit 81""")
        writeFakeSkip("""
            echo resolved >> "${invocationMarker.absolutePath}"
            cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
        """, version = "9.9.9", name = "new-skip")
        val build = runner(active,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", exact: "1.9.3"),""",
        )
        val lockfile = File(pkgDir, "Package.resolved")
        val original = """{"pins":[{"identity":"skip","state":{"version":"9.9.9"}}]}"""
        lockfile.writeText(original)
        assertEquals(TaskOutcome.SUCCESS, build.build().task(":exportTest")?.outcome)
        assertEquals(listOf("resolved"), invocationMarker.readLines())
        assertContains(File(projectDir, "brew-calls").readText(), "install --formula skip")
        assertEquals(original, lockfile.readText())
    }

    @Test
    fun `credential failure on the resolved CLI never upgrades past that pin`() {
        val active = writeFakeSkip("""
            echo export >> "${invocationMarker.absolutePath}"
            echo "Failed to find credentials in keychain: status -25308" >&2
            exit 1
        """)
        val build = runner(active,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", from: "1.9.0"),""",
        )
        File(pkgDir, "Package.resolved").writeText("""{"pins":[{"identity":"skip","state":{"version":"1.9.3"}}]}""")
        val result = build.buildAndFail()
        assertContains(result.output, "status -25308")
        assertEquals(listOf("export"), invocationMarker.readLines())
        assertFalse(File(projectDir, "brew-calls").exists())
    }

    @Test
    fun `failed export after installing resolved CLI does not install a different version`() {
        val active = writeFakeSkip("exit 81")
        writeFakeSkip("""
            echo resolved >> "${invocationMarker.absolutePath}"
            echo "compilation failed" >&2
            exit 1
        """, version = "9.9.9", name = "new-skip")
        val build = runner(active,
            packageSwift = """.package(url: "https://github.com/skiptools/skip.git", from: "1.9.0"),""",
        )
        File(pkgDir, "Package.resolved").writeText("""{"pins":[{"identity":"skip","state":{"version":"9.9.9"}}]}""")
        val result = build.buildAndFail()
        assertContains(result.output, "compilation failed")
        assertEquals(listOf("resolved"), invocationMarker.readLines())
        assertEquals(1, File(projectDir, "brew-calls").readLines().count { it == "update" })
    }

    private fun runner(
        fakeSkip: File,
        packageSwift: String = "// swift-tools-version:5.9",
        extraTaskConfig: String = "",
        extraEnv: Map<String, String> = emptyMap(),
    ): GradleRunner {
        File(pkgDir, "Sources").mkdirs()
        File(pkgDir, "Sources/placeholder.swift").writeText("// swift source")
        File(pkgDir, "Package.swift").writeText(packageSwift)
        File(projectDir, "settings.gradle.kts").writeText("rootProject.name = \"export-test\"")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins { id("com.theleftbit.skipspm") apply false }

            tasks.register("exportTest", com.theleftbit.skipspm.SkipExportTask::class.java) {
                packageDir.set(layout.projectDirectory.dir("pkg"))
                sources.set(layout.projectDirectory.dir("pkg/Sources"))
                manifests.from(layout.projectDirectory.file("pkg/Package.swift"), layout.projectDirectory.file("pkg/Package.resolved"))
                module.set("TestModule")
                buildMode.set("debug")
                abis.set(listOf("arm64-v8a"))
                namespacePrefix.set("com.test.shared")
                outputDir.set(layout.projectDirectory.dir("lib/debug"))
                $extraTaskConfig
            }
            """.trimIndent(),
        )
        return GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("exportTest")
            .withEnvironment(System.getenv() + mapOf("SKIP_PATH" to fakeSkip.absolutePath, "SKIP_BREW_PATH" to writeFakeBrew().absolutePath) + extraEnv)
    }

    /** Never let functional tests inspect or mutate the host Homebrew installation. */
    private fun writeFakeBrew(): File = File(projectDir, "fake-brew").apply {
        writeText("""
            #!/bin/bash
            set -eu
            echo "${'$'}*" >> "${File(projectDir, "brew-calls").absolutePath}"
            test "${'$'}HOMEBREW_NO_INSTALL_CLEANUP" = 1
            test "${'$'}HOMEBREW_NO_AUTO_UPDATE" = 1
            case "${'$'}1" in
              --cellar) echo "${File(projectDir, "brew/Cellar").absolutePath}" ;;
              --prefix) echo "${File(projectDir, "brew").absolutePath}" ;;
              update) ;;
              info) echo "{\"formulae\":[{\"versions\":{\"stable\":\"${'$'}{TEST_SKIP_LATEST:-9.9.9}\"}}]}" ;;
              install|upgrade)
                test "${'$'}2" = --formula
                test "${'$'}3" = skip
                mkdir -p "${File(projectDir, "brew/Cellar/skip/9.9.9/bin").absolutePath}"
                cp "${File(projectDir, "new-skip").absolutePath}" "${File(projectDir, "brew/Cellar/skip/9.9.9/bin/skip").absolutePath}"
                ;;
              *) exit 91 ;;
            esac
        """.trimIndent())
        setExecutable(true)
    }

    /** A fake `skip` CLI: parses `-d <out>` like the real one, then runs [body]. */
    private fun writeFakeSkip(body: String, version: String = "1.9.3", name: String = "fake-skip"): File {
        val script = File(projectDir, name)
        script.writeText(
            buildString {
                appendLine("#!/bin/bash")
                appendLine("if [ \"\$1\" = \"version\" ]; then echo \"Skip version $version\"; exit 0; fi")
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
