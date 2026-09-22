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
        !exact && compareDottedVersions(cliVersion, version) < 0 ->
            "the skip CLI is $cliVersion but the package requires at least skip $version " +
                "(Package.swift). Update the skip CLI."
        else -> null
    }
}

/**
 * Package.resolved is authoritative: its Skip pin is the exact CLI target, regardless of the
 * manifest's from/exact requirement. As before, Package.swift is a fallback only when there is
 * no usable resolved Skip version. Returns null when neither file declares a version.
 */
internal fun expectedSkipVersion(packageSwift: String?, packageResolved: String?): SkipVersionRequirement? {
    packageResolved?.let { resolved ->
        val document = runCatching { JsonSlurper().parseText(resolved) as? Map<*, *> }.getOrNull()
        val pin = (document?.get("pins") as? List<*>)?.filterIsInstance<Map<*, *>>()
            ?.firstOrNull { it["identity"] == "skip" }
        val version = (pin?.get("state") as? Map<*, *>)?.get("version") as? String
        if (version != null) return SkipVersionRequirement(version, exact = true)
    }
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
    return null
}

/** Read only the CLI version line; warnings may contain unrelated tool versions. */
internal fun parseSkipCliVersion(output: String): String? =
    Regex("""(?m)^Skip version (\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?)\s*$""")
        .find(output)?.groupValues?.get(1)

/** Semantic version precedence, ignoring build metadata; missing core segments count as zero. */
internal fun compareDottedVersions(a: String, b: String): Int {
    val aVersion = a.substringBefore('+')
    val bVersion = b.substringBefore('+')
    val aParts = aVersion.substringBefore('-').split('.')
    val bParts = bVersion.substringBefore('-').split('.')
    repeat(maxOf(aParts.size, bParts.size)) { i ->
        val diff = aParts.getOrElse(i) { "0" }.toBigInteger()
            .compareTo(bParts.getOrElse(i) { "0" }.toBigInteger())
        if (diff != 0) return diff
    }
    val aPre = aVersion.substringAfter('-', "")
    val bPre = bVersion.substringAfter('-', "")
    if (aPre == bPre) return 0
    if (aPre.isEmpty()) return 1
    if (bPre.isEmpty()) return -1
    val aIdentifiers = aPre.split('.')
    val bIdentifiers = bPre.split('.')
    repeat(minOf(aIdentifiers.size, bIdentifiers.size)) { i ->
        val left = aIdentifiers[i]
        val right = bIdentifiers[i]
        val leftNumber = left.takeIf { it.all(Char::isDigit) }?.toBigIntegerOrNull()
        val rightNumber = right.takeIf { it.all(Char::isDigit) }?.toBigIntegerOrNull()
        val diff = when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right)
        }
        if (diff != 0) return diff
    }
    return aIdentifiers.size.compareTo(bIdentifiers.size)
}

/** Matches `…/skip.git", exact: "1.9.3"`, `…, from: "1.9.3"`, and `…, .upToNextMajor(from: "1.9.3")`. */
private val MANIFEST_SKIP_REQUIREMENT = Regex(
    """"[^"]*/skip\.git"\s*,\s*(?:(exact|from)\s*:\s*"([^"]+)"|\.upToNext(?:Major|Minor)\s*\(\s*from:\s*"([^"]+)"\s*\))""",
)
