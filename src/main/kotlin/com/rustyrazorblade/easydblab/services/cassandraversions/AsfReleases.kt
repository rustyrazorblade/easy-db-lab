package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.Constants
import kotlinx.serialization.Serializable

/**
 * One Apache release line for `cassandra list`: its newest GA release and every GA patch, oldest
 * first, so the hundred-plus releases fit on one screen.
 */
data class AsfReleaseLine(
    val line: String,
    val latest: String,
    val patches: List<String>,
)

/**
 * The Apache Cassandra releases: every directory name of the archive index. A release line
 * (`5.0`) resolves to its highest GA patch (`5.0.9`); a pre-release (`-rc1`, `-beta1`) is never
 * picked for a line, but installs when named exactly.
 */
@Serializable
data class AsfReleases(
    val names: List<String>,
) {
    /** The release [name] installs: itself when the archive holds it, else the newest GA patch of the line [name]. */
    fun resolve(name: String): String? =
        when {
            name in names -> name
            LINE.matches(name) -> gaPatches(name).lastOrNull()
            else -> null
        }

    /** Every line with at least one GA patch release, the newest line first. */
    fun lines(): List<AsfReleaseLine> =
        names
            .mapNotNull { GA_PATCH.matchEntire(it)?.groupValues?.get(1) }
            .distinct()
            .sortedWith(compareByDescending(VERSION_ORDER) { it })
            .map { line -> gaPatches(line).let { AsfReleaseLine(line, it.last(), it) } }

    private fun gaPatches(line: String): List<String> =
        names
            .filter { GA_PATCH.matchEntire(it)?.groupValues?.get(1) == line }
            .sortedWith(VERSION_ORDER)

    companion object {
        private val LINE = Regex("""\d+\.\d+""")
        private val GA_PATCH = Regex("""(\d+\.\d+)\.\d+""")
        private val INDEX_DIRECTORY = Regex("""href="(\d[^"/?]*)/"""")
        private val RELEASE_TARBALL = Regex("""/([^/]+)/apache-cassandra-\1-bin\.tar\.gz$""")

        private val VERSION_ORDER: Comparator<String> =
            Comparator { a, b ->
                val left = a.split(".").map { it.toIntOrNull() ?: 0 }
                val right = b.split(".").map { it.toIntOrNull() ?: 0 }
                left
                    .zip(right)
                    .map { (l, r) -> l.compareTo(r) }
                    .firstOrNull { it != 0 }
                    ?: left.size.compareTo(right.size)
            }

        /** The release directories linked from the archive's index page; other links are ignored. */
        fun parseIndex(html: String): AsfReleases =
            AsfReleases(
                INDEX_DIRECTORY
                    .findAll(html)
                    .map { it.groupValues[1] }
                    .distinct()
                    .toList(),
            )

        /** The tarball of [release] on the CDN, which holds only the current releases. */
        fun downloadUrl(release: String): String = "${Constants.Cassandra.ASF_DLCDN_BASE}/$release/${tarball(release)}"

        /** The tarball of [release] on the archive, which holds every release. */
        fun archiveUrl(release: String): String = "${Constants.Cassandra.ASF_ARCHIVE_BASE}/$release/${tarball(release)}"

        /** The release of a [downloadUrl] or [archiveUrl], or null for any other URL. */
        fun releaseOf(url: String): String? = RELEASE_TARBALL.find(url)?.groupValues?.get(1)

        /** The archive's index page, the release list. */
        fun indexUrl(): String = "${Constants.Cassandra.ASF_ARCHIVE_BASE}/"

        /** The key of [release]'s tarball under the account download cache root. */
        fun cacheKey(release: String): String = "cassandra-dist/$release/${tarball(release)}"

        private fun tarball(release: String) = "apache-cassandra-$release-bin.tar.gz"
    }
}
