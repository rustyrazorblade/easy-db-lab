package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.configuration.CassandraSource

/** One row of `cassandra list`: a name that can be installed, where it comes from, and the nodes that have it. */
data class VersionRow(
    val version: String,
    val source: String,
    val details: String = "",
    /** The nodes that have it: `all`, the aliases, or blank; null when there is no cluster. */
    val installed: String? = null,
)

/**
 * What the db nodes reported for `cassandra list`: each listed node's versions, the error of each
 * node that could not be listed, and the source of each version the cluster state records.
 */
data class ClusterListing(
    val perNode: Map<String, List<String>>,
    val unreachable: Map<String, String> = emptyMap(),
    val recordedSources: Map<String, CassandraSource> = emptyMap(),
)

/**
 * Builds the one table of `cassandra list` (design D9) from the version sources and, when a cluster
 * exists, what its nodes have. Pure, so the rules are tested without a cluster or a network.
 */
object VersionTable {
    private const val ALL = "all"
    private const val GAP = 2

    /** The source label of a version the state records but no source lists. */
    fun label(source: CassandraSource): String =
        when (source) {
            CassandraSource.ASF -> "asf"
            CassandraSource.CASSANDRA_BUILDS -> "cassandra-builds"
            CassandraSource.BUILD_CATALOG -> "build"
            CassandraSource.PROFILE -> "profile"
            CassandraSource.URL -> "url"
            CassandraSource.GIT -> "git"
        }

    /**
     * Rows in source order: the profile builds, one row per ASF release line (its latest release and
     * its patches), the cassandra-builds builds, the catalog builds, then every installed version
     * that no source lists. A name already listed by an earlier source is not listed again.
     */
    fun rows(
        profile: List<ProfileBuild>,
        asf: AsfReleases,
        cassandraBuilds: List<CassandraBuild>,
        catalog: List<String>,
        cluster: ClusterListing?,
    ): List<VersionRow> {
        val fromSources =
            profile.map { VersionRow(it.version, label(CassandraSource.PROFILE), "JDK ${it.jdks().joinToString(", ")}") } +
                asf.lines().map {
                    VersionRow(it.line, label(CassandraSource.ASF), "latest ${it.latest}; releases ${it.patches.joinToString(", ")}")
                } +
                cassandraBuilds.map { VersionRow(it.ref, label(CassandraSource.CASSANDRA_BUILDS)) } +
                catalog.map { VersionRow(it, label(CassandraSource.BUILD_CATALOG)) }
        val listed = fromSources.distinctBy { it.version }
        val names = listed.map { it.version }.toSet()
        val unlisted =
            cluster
                ?.perNode
                ?.values
                ?.flatten()
                ?.distinct()
                ?.sorted()
                ?.filter { it !in names }
                ?.map { VersionRow(it, cluster.recordedSources[it]?.let(::label) ?: "unknown") }
                .orEmpty()
        return (listed + unlisted).map { row -> cluster?.let { row.copy(installed = installedOn(row.version, it)) } ?: row }
    }

    private fun installedOn(
        version: String,
        cluster: ClusterListing,
    ): String {
        val have = cluster.perNode.filterValues { version in it }.keys
        return when {
            have.isEmpty() -> ""
            have.size == cluster.perNode.size && cluster.unreachable.isEmpty() -> ALL
            else -> have.joinToString(",")
        }
    }

    /** The table as aligned text, with the `INSTALLED` column only when there is a cluster. */
    fun render(rows: List<VersionRow>): String {
        val withCluster = rows.any { it.installed != null }
        val header = listOfNotNull("VERSION", "SOURCE", "INSTALLED".takeIf { withCluster }, "DETAILS")
        val body = rows.map { listOfNotNull(it.version, it.source, it.installed?.takeIf { withCluster }, it.details) }
        val table = listOf(header) + body
        val widths = header.indices.map { column -> table.maxOf { it.getOrElse(column) { "" }.length } }
        return table.joinToString("\n") { cells ->
            cells
                .mapIndexed { column, cell -> if (column == cells.lastIndex) cell else cell.padEnd(widths[column] + GAP) }
                .joinToString("")
                .trimEnd()
        }
    }
}
