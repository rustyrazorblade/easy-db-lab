package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.Constants
import java.io.InputStream
import java.security.MessageDigest

/**
 * The HTTP reads [TarballDigests] needs: a small text file, and a streamed body for the one case
 * that must hash a download. A port, so the digest rules are tested without a network.
 */
interface ChecksumHttp {
    /** The body at [url] as text, or null when the server answers 404. */
    fun getOrNull(url: String): String?

    /** [read] applied to the body at [url] as a stream, or null when the server answers 404. */
    fun <T> streamOrNull(
        url: String,
        read: (InputStream) -> T,
    ): T?
}

/**
 * Where a tarball's sha256 comes from, one function per source. The pod's cache key is always a
 * sha256, whatever the source publishes:
 *
 * - ASF: the published `.sha256`; when only a `.sha512` is published, one download is checked
 *   against it and its sha256 is computed from that same download.
 * - cassandra-builds: the GitHub release asset's `digest` (`sha256:<hex>`).
 * - build catalog: the manifest's `tarballSha256`.
 *
 * Every failure is a [VersionResolutionException] naming the tarball, so `start` fails before any
 * resource exists.
 */
class TarballDigests(
    private val http: ChecksumHttp,
) {
    /** The sha256 of the ASF tarball of [release], from the CDN or else the archive. */
    fun asf(release: String): Sha256 {
        val urls = listOf(AsfReleases.downloadUrl(release), AsfReleases.archiveUrl(release))
        return urls.firstNotNullOfOrNull { url -> http.getOrNull("$url.sha256")?.let(::firstWord)?.let(Sha256::parse) }
            ?: urls.firstNotNullOfOrNull { url -> fromSha512(url) }
            ?: throw VersionResolutionException(
                "No sha256 or sha512 is published for the Cassandra tarball ${urls.first()} (or ${urls.last()}), " +
                    "so its download cannot be checked.",
            )
    }

    /** The tarball at [url], checked against its published `.sha512`, as a sha256; null when there is no `.sha512`. */
    private fun fromSha512(url: String): Sha256? {
        val expected = http.getOrNull("$url.sha512")?.let(::firstWord)?.lowercase() ?: return null
        val (sha512, sha256) =
            http.streamOrNull(url, ::digests)
                ?: throw VersionResolutionException("The Cassandra tarball $url could not be downloaded to compute its sha256.")
        if (sha512 != expected) {
            throw VersionResolutionException(
                "The Cassandra tarball $url does not match its published sha512: expected $expected, got $sha512.",
            )
        }
        return Sha256(sha256)
    }

    companion object {
        private const val GITHUB_PREFIX = "sha256:"

        /** The sha256 of a GitHub release asset, from its `digest` field. */
        fun github(
            digest: String?,
            url: String,
        ): Sha256 =
            digest
                ?.takeIf { it.startsWith(GITHUB_PREFIX) }
                ?.let { Sha256.parse(it.removePrefix(GITHUB_PREFIX)) }
                ?: throw VersionResolutionException(
                    "cassandra-builds publishes no sha256 digest for $url (got '${digest.orEmpty()}'), so its download cannot be checked.",
                )

        /** The sha256 a catalog build's manifest records. */
        fun catalog(
            tarballSha256: String,
            name: String,
        ): Sha256 =
            Sha256.parse(tarballSha256)
                ?: throw VersionResolutionException(
                    "The build catalog's manifest for $name has no valid tarballSha256 ('$tarballSha256'), so its download cannot be checked.",
                )

        private fun firstWord(text: String): String? =
            text
                .trim()
                .split(Regex("\\s+"))
                .firstOrNull()
                ?.ifEmpty { null }

        /** The sha512 and the sha256 of [input], read once. */
        private fun digests(input: InputStream): Pair<String, String> {
            val sha512 = MessageDigest.getInstance("SHA-512")
            val sha256 = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(Constants.Cassandra.DIGEST_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sha512.update(buffer, 0, read)
                sha256.update(buffer, 0, read)
            }
            return hex(sha512.digest()) to hex(sha256.digest())
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
