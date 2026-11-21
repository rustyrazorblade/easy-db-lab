package com.rustyrazorblade.easydblab.services.cassandraversions

/**
 * A tarball's sha256, as 64 lowercase hex characters: the key a pod caches the extracted tree under
 * and the hash its fetch checks the download against. Checked on construction, so a value that is
 * not a sha256 can never reach a pod.
 */
@JvmInline
value class Sha256(
    val hex: String,
) {
    init {
        require(PATTERN.matches(hex)) { "'$hex' is not a sha256: it must be 64 lowercase hex characters" }
    }

    companion object {
        private val PATTERN = Regex("[0-9a-f]{64}")

        /** [value] trimmed and lowercased as a [Sha256], or null when it is not one. */
        fun parse(value: String?): Sha256? =
            value
                ?.trim()
                ?.lowercase()
                ?.takeIf(PATTERN::matches)
                ?.let(::Sha256)
    }
}

/**
 * The Cassandra tarball a cassandra-pods pod runs, resolved by the CLI before any resource exists:
 * where to download it, what it must hash to, the build label its metrics carry, and the JDK
 * (which picks the `cassandra-runtime` image tag).
 */
data class TarballArtifact(
    val url: String,
    val fallbackUrl: String?,
    val sha256: Sha256,
    val build: String,
    val java: String,
)
