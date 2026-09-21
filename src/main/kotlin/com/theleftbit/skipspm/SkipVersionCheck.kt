package com.theleftbit.skipspm

import groovy.json.JsonSlurper

/**
 * The skip toolchain version a package declares, extracted from `Package.resolved` /
 * `Package.swift`. When the `skip` CLI (Homebrew) and the pinned `skip` SwiftPM package (the
 * skipstone transpiler — same repo, same version stream) drift apart, exports fail with cryptic,
 * far-away errors (e.g. unresolved bridging symbols in generated Kotlin), so the export preflights
 * the comparison and reports the drift in plain terms.
 */
internal data class SkipVersionRequirement(
    val version: String,
    /** True for a resolved pin or an `exact:` requirement; false for `from:`-style minimums. */
    val exact: Boolean,
) {
    /** A human-readable description of the mismatch with [cliVersion], or null when compatible. */
    fun mismatchWith(cliVersion: String): String? = when {
        exact && cliVersion != version ->
            "the skip CLI is $cliVersion but the package pins skip $version " +
                "(Package.swift/Package.resolved). Align them: update the pin to $cliVersion, or " +
                "install skip $version."
        !exact && (compareDottedVersions(cliVersion, version) < 0 ||
            (compareDottedVersions(cliVersion, version) == 0 && '-' in cliVersion && '-' !in version)) ->
            "the skip CLI is $cliVersion but the package requires at least skip $version " +
                "(Package.swift). Update the skip CLI."
        else -> null
    }
}

/**
 * The project's Package.swift requirement controls CLI compatibility. A resolved pin is only
 * a fallback when the manifest declares no recognized version requirement on skip.git.
 * This lets projects share any installed CLI meeting their declared minimum, regardless of
 * which library version SwiftPM resolved. Returns null when neither declares a version.
 */
internal fun expectedSkipVersion(packageSwift: String?, packageResolved: String?): SkipVersionRequirement? {
    packageSwift?.let { manifest ->
        MANIFEST_SKIP_REQUIREMENT.find(manifest)?.let { match ->
            val (label, labeledVersion, rangeVersion) = match.destructured
            return if (label == "exact") {
                SkipVersionRequirement(labeledVersion, exact = true)
            } else {
                SkipVersionRequirement(labeledVersion.ifEmpty { rangeVersion }, exact = false)
            }
        }
    }
    packageResolved?.let { resolved ->
        val document = runCatching { JsonSlurper().parseText(resolved) as? Map<*, *> }.getOrNull()
        val pin = (document?.get("pins") as? List<*>)?.filterIsInstance<Map<*, *>>()
            ?.firstOrNull { it["identity"] == "skip" }
        val version = (pin?.get("state") as? Map<*, *>)?.get("version") as? String
        if (version != null) return SkipVersionRequirement(version, exact = true)
    }
    return null
}

/** Read only the CLI version line; warnings may contain unrelated tool versions. */
internal fun parseSkipCliVersion(output: String): String? =
    Regex("""(?m)^Skip version (\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?)\s*$""")
        .find(output)?.groupValues?.get(1)

/** Numeric segment-wise comparison; missing segments count as 0 (`1.9` == `1.9.0`). */
internal fun compareDottedVersions(a: String, b: String): Int {
    val aParts = a.substringBefore('-').substringBefore('+').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val bParts = b.substringBefore('-').substringBefore('+').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    repeat(maxOf(aParts.size, bParts.size)) { i ->
        val diff = aParts.getOrElse(i) { 0 } - bParts.getOrElse(i) { 0 }
        if (diff != 0) return diff
    }
    return 0
}

/** Matches `…/skip.git", exact: "1.9.3"`, `…, from: "1.9.3"`, and `…, .upToNextMajor(from: "1.9.3")`. */
private val MANIFEST_SKIP_REQUIREMENT = Regex(
    """"[^"]*/skip\.git"\s*,\s*(?:(exact|from)\s*:\s*"([^"]+)"|\.upToNext(?:Major|Minor)\s*\(\s*from:\s*"([^"]+)"\s*\))""",
)
