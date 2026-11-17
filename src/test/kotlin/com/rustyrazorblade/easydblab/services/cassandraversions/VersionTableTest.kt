package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.configuration.CassandraSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The rules of the `cassandra list` table (design D9), on synthetic source lists. */
class VersionTableTest {
    private val profile = listOf(ProfileBuild(version = "my-build", url = "https://x.test/a.tgz", javaSupported = listOf("11", "17")))
    private val asf = AsfReleases(listOf("4.1.6", "4.1.7", "5.0.5", "5.0.6", "5.0-rc1"))
    private val nightly = listOf(CassandraBuild("5.0-HEAD", "https://gh/5.0-HEAD.tgz"), CassandraBuild("trunk", "https://gh/trunk.tgz"))
    private val catalog = listOf("5.1-20261101-abc1234-jdk17")

    private fun rows(cluster: ClusterListing? = null) = VersionTable.rows(profile, asf, nightly, catalog, cluster)

    @Test
    fun `with no cluster, every source is listed in order with its label, one row per ASF line`() {
        val rows = rows()

        assertThat(rows.map { it.version to it.source }).containsExactly(
            "my-build" to "profile",
            "5.0" to "asf",
            "4.1" to "asf",
            "5.0-HEAD" to "cassandra-builds",
            "trunk" to "cassandra-builds",
            "5.1-20261101-abc1234-jdk17" to "build",
        )
        assertThat(rows.single { it.version == "5.0" }.details).isEqualTo("latest 5.0.6; releases 5.0.5, 5.0.6")
        assertThat(rows).allMatch { it.installed == null }
        assertThat(VersionTable.render(rows).lines().first()).doesNotContain("INSTALLED")
    }

    /** `db0` and `db1` have 5.0, `db2` also has trunk; [unreachable] adds a node that could not be listed. */
    @ParameterizedTest(name = "{0} unreachable={1} -> {2}")
    @CsvSource(
        "5.0,      false, all",
        "trunk,    false, db2",
        "my-build, false, ''",
        "5.0,      true,  'db0,db1,db2'",
    )
    fun `the installed column names the nodes, and says all only when every node was listed`(
        version: String,
        unreachable: Boolean,
        expected: String,
    ) {
        val cluster =
            ClusterListing(
                perNode = mapOf("db0" to listOf("5.0"), "db1" to listOf("5.0"), "db2" to listOf("5.0", "trunk")),
                unreachable = if (unreachable) mapOf("db3" to "timed out") else emptyMap(),
            )

        assertThat(rows(cluster).single { it.version == version }.installed).isEqualTo(expected)
    }

    @Test
    fun `an installed version no source lists is shown with its recorded source`() {
        val cluster =
            ClusterListing(
                perNode = mapOf("db0" to listOf("mine", "orphan")),
                recordedSources = mapOf("mine" to CassandraSource.URL),
            )

        val tail = rows(cluster).takeLast(2)

        assertThat(tail.map { Triple(it.version, it.source, it.installed) })
            .containsExactly(Triple("mine", "url", "all"), Triple("orphan", "unknown", "all"))
    }

    @Test
    fun `a name two sources list appears once, from the first source`() {
        val shadowing = listOf(ProfileBuild(version = "trunk", url = "https://x.test/t.tgz", java = "17"))

        val rows = VersionTable.rows(shadowing, asf, nightly, catalog, null)

        assertThat(rows.filter { it.version == "trunk" }.map { it.source }).containsExactly("profile")
    }

    @Test
    fun `the rendered columns line up`() {
        val text = VersionTable.render(rows(ClusterListing(perNode = mapOf("db0" to listOf("5.0")))))

        val lines = text.lines()
        assertThat(lines.first()).startsWith("VERSION").contains("SOURCE", "INSTALLED", "DETAILS")
        val sourceColumn = lines.first().indexOf("SOURCE")
        assertThat(lines.drop(1)).allSatisfy { assertThat(it.substring(sourceColumn)).doesNotStartWith(" ") }
    }
}
