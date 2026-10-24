package com.theleftbit.skipspm

import org.gradle.api.GradleException
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Replaces each AAR's `jni/<abi>/lib_FoundationICU.so` with a copy relinked from the Swift Android
 * SDK's static archive whose ICU data keeps only what [languages] need (see [keepsIcuItem]). The
 * SDK's copy embeds ICU data for every locale and feature: 34 MB per ABI.
 */
internal class FoundationIcuTrimmer(
    private val exec: ExecOperations,
    private val packageDir: File,
    private val languages: List<String>,
    private val workDir: File,
) {
    private val sdk by lazy { findSwiftAndroidSdk(packageDir, System.getProperty("user.home").orEmpty()) }
    private val toolchainBin by lazy {
        sdk.listFiles { f -> f.name.startsWith("android-ndk-") }.orEmpty()
            .flatMap { File(it, "toolchains/llvm/prebuilt").listFiles().orEmpty().toList() }
            .map { File(it, "bin") }
            .singleOrNull { it.isDirectory }
            ?: throw GradleException("skipSpm: no NDK toolchain (android-ndk-*/toolchains/llvm/prebuilt/*/bin) in $sdk.")
    }

    /** Trimmed libraries by `<abi>/<minSdk>`, so several AARs bundling the same one relink it once. */
    private val trimmed = mutableMapOf<String, File>()

    fun trim(aar: File) {
        val (abis, minSdk) = ZipFile(aar).use { zip ->
            val abis = zip.entries().asSequence().map { it.name }
                .filter { it.startsWith("jni/") && it.endsWith("/$LIB") }
                .map { it.removePrefix("jni/").removeSuffix("/$LIB") }
                .toList()
            val manifest = zip.getEntry("AndroidManifest.xml")?.let { zip.getInputStream(it).readBytes().decodeToString() }
            // Skip's AARs declare their minSdk; 28 is Skip's own default for the rare one that doesn't.
            abis to (manifest?.let { MIN_SDK.find(it)?.groupValues?.get(1)?.toInt() } ?: 28)
        }
        if (abis.isEmpty()) return
        FileSystems.newFileSystem(URI.create("jar:${aar.toURI()}"), emptyMap<String, Any>()).use { fs ->
            abis.forEach { abi ->
                val lib = trimmed.getOrPut("$abi/$minSdk") { relink(abi, minSdk) }
                Files.copy(lib.toPath(), fs.getPath("jni/$abi/$LIB"), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun relink(abi: String, minSdk: Int): File {
        val (arch, triple) = ARCHES[abi]
            ?: throw GradleException("skipSpm: icuLanguages can't trim $LIB for ABI '$abi' (supported: ${ARCHES.keys}).")
        val archive = File(sdk, "swift-resources/usr/lib/swift_static-$arch/android/lib_FoundationICU.a")
        if (!archive.isFile) throw GradleException("skipSpm: icuLanguages needs $archive, which is missing.")
        val work = File(workDir, "$abi-$minSdk").apply {
            deleteRecursively()
            mkdirs()
        }
        tool(work, "llvm-ar", "x", archive.path)
        val dataObject = File(work, "icu_packaged_data.cpp.o")
        val symbol = tool(work, "llvm-nm", "--defined-only", dataObject.path).lines()
            .map { it.substringAfterLast(' ') }
            .single { it.startsWith("icudt") && it.endsWith("_dat") }
        val original = File(work, "icudt.dat")
        tool(work, "llvm-objcopy", "-O", "binary", "--only-section=.rodata.$symbol", dataObject.path, original.path)
        dataObject.delete()
        File(work, "icudt.trimmed").writeBytes(trimIcuData(original.readBytes()) { keepsIcuItem(it, languages) })
        File(work, "icudt.S").writeText(
            """
            .section .rodata.$symbol,"a"
            .globl $symbol
            .type $symbol,%object
            .balign 16
            $symbol:
            .incbin "icudt.trimmed"
            .size $symbol, .-$symbol
            """.trimIndent() + "\n",
        )
        val target = "--target=$triple$minSdk"
        tool(work, "clang", target, "-c", "icudt.S", "-o", "icudt_trimmed.o")
        val objects = work.listFiles { f -> f.extension == "o" }!!.map { it.name }.sorted()
        val lib = File(work, LIB)
        tool(
            work, "clang++", target, "-shared", "-static-libstdc++",
            "-Wl,-soname,$LIB", "-Wl,--build-id=sha1", "-o", lib.path, *objects.toTypedArray(),
        )
        return lib
    }

    private fun tool(work: File, name: String, vararg arguments: String): String {
        val stdout = ByteArrayOutputStream()
        exec.exec {
            workingDir = work
            executable = File(toolchainBin, name).path
            args(*arguments)
            standardOutput = stdout
        }
        return stdout.toString()
    }

    private companion object {
        const val LIB = "lib_FoundationICU.so"
        val MIN_SDK = Regex("minSdkVersion=\"(\\d+)\"")

        /** Android ABI → (SDK arch dir suffix, clang target triple without API level). */
        val ARCHES = mapOf(
            "arm64-v8a" to ("aarch64" to "aarch64-linux-android"),
            "armeabi-v7a" to ("armv7" to "armv7a-linux-androideabi"),
            "x86_64" to ("x86_64" to "x86_64-linux-android"),
        )
    }
}

/**
 * The Swift Android SDK `skip export` builds with: the one named by the nearest `.swift-version`
 * at or above [packageDir], else the only installed one.
 */
internal fun findSwiftAndroidSdk(packageDir: File, home: String): File {
    val roots = listOf("$home/Library/org.swift.swiftpm/swift-sdks", "$home/.swiftpm/swift-sdks", "/root/.swiftpm/swift-sdks")
        .map(::File)
    val version = generateSequence(packageDir.absoluteFile) { it.parentFile }
        .map { File(it, ".swift-version") }
        .firstOrNull { it.isFile }
        ?.readText()?.trim()
    val bundles = roots.flatMap { root ->
        root.listFiles { f -> f.name.endsWith("_android.artifactbundle") }.orEmpty().toList()
    }
    val bundle = if (version != null) {
        bundles.firstOrNull { it.name == "swift-$version-RELEASE_android.artifactbundle" }
    } else {
        bundles.firstOrNull()?.takeIf { only -> bundles.all { it.name == only.name } }
    }
    return bundle?.let { File(it, "swift-android") }?.takeIf { it.isDirectory }
        ?: throw GradleException(
            "skipSpm: icuLanguages needs the Swift Android SDK " +
                (version?.let { "for Swift $it (from .swift-version) " } ?: "(add a .swift-version to pick one) ") +
                "under ${roots.joinToString()}; found ${bundles.map { it.name }.distinct()}.",
        )
}

/**
 * Whether the ICU data item at [path] (e.g. `coll/en.res`, without the `icudtNNl/` prefix) is kept
 * when trimming to [languages]:
 *  - dropped: break dictionaries (`*.dict`), legacy charset converters (`*.cnv`, `cnvalias.icu`),
 *    character names (`unames.icu`) and every transliterator (`translit/`);
 *  - in the per-locale trees (`coll`, `rbnf`, `lang`, `region`, `unit`, `zone`, `curr`): only
 *    `root`, `pool`, `res_index`, the listed languages and their `_REGION`/`_Script` variants, plus
 *    any file not named after a locale;
 *  - everything else, including EVERY locale's top-level `.res`, so dates keep following the
 *    device locale. `rbnf` must stay for the listed languages: corelibs `NumberFormatter` crashes
 *    (SIGTRAP) on `.ordinal` without it.
 */
internal fun keepsIcuItem(path: String, languages: List<String>): Boolean {
    val parts = path.split('/')
    val file = parts.last()
    val kept = Regex("(root|pool|res_index|${languages.joinToString("|") { "$it|${it}_\\w+" }})\\.res")
    return when {
        file.endsWith(".dict") || file.endsWith(".cnv") || file in setOf("unames.icu", "cnvalias.icu") -> false
        parts.size == 2 && parts[0] == "translit" -> false
        parts.size == 2 && parts[0] in LOCALE_TREES -> kept.matches(file) || !LOCALE_NAMED.matches(file)
        else -> true
    }
}

private val LOCALE_TREES = setOf("coll", "rbnf", "lang", "region", "unit", "zone", "curr")
private val LOCALE_NAMED = Regex("[a-z]{2,3}(_\\w+)?\\.res")

/** The (name, bytes) items of an ICU common-data blob (`icudtNN.dat`), in table-of-contents order. */
internal fun readIcuItems(data: ByteArray): List<Pair<String, ByteArray>> {
    val input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val toc = input.getShort(0).toInt() and 0xffff
    val count = input.getInt(toc)
    fun cString(at: Int) = String(data, at, (at until data.size).first { data[it] == 0.toByte() } - at)
    val bounds = (0 until count).map { input.getInt(toc + 8 + 8 * it) } + (data.size - toc)
    return (0 until count).map {
        cString(toc + input.getInt(toc + 4 + 8 * it)) to data.copyOfRange(toc + bounds[it], toc + bounds[it + 1])
    }
}

/**
 * Rewrites an ICU common-data blob keeping only the items whose name (minus the `icudtNNl/`
 * prefix) passes [keep]. Kept items stay in their original, sorted order (ICU binary-searches the
 * table of contents) and 16-byte aligned; the header is copied verbatim.
 */
internal fun trimIcuData(data: ByteArray, keep: (String) -> Boolean): ByteArray {
    val toc = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort(0).toInt() and 0xffff
    val items = readIcuItems(data).filter { (name, _) -> keep(name.substringAfter('/')) }
    val tableSize = 4 + 8 * items.size
    val namesSize = items.sumOf { it.first.toByteArray().size + 1 }
    val firstItem = (tableSize + namesSize + 15) and 15.inv()
    val output = ByteBuffer.allocate(toc + firstItem + items.sumOf { (it.second.size + 15) and 15.inv() })
        .order(ByteOrder.LITTLE_ENDIAN)
    output.put(data, 0, toc).putInt(items.size)
    var nameOffset = tableSize
    var itemOffset = firstItem
    items.forEach { (name, bytes) ->
        output.putInt(nameOffset).putInt(itemOffset)
        nameOffset += name.toByteArray().size + 1
        itemOffset += (bytes.size + 15) and 15.inv()
    }
    items.forEach { (name, _) -> output.put(name.toByteArray()).put(0) }
    output.position(toc + firstItem)
    items.forEach { (_, bytes) -> output.put(bytes).position((output.position() - toc + 15 and 15.inv()) + toc) }
    return output.array()
}
