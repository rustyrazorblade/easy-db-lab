package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.configuration.grafana.ClusterFilterGuards.Language
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * No dashboard pairs a host of one cluster with the host of the same name in another cluster.
 *
 * With `cluster` set to All, two clusters that both have an app0 made System Overview's "Load per
 * core" fail with "found duplicate series for the match group {host_name=\"app0\"}".
 */
class ClusterJoinTest {
    @Test
    fun `a match on the host alone is reported, and one on cluster and host is not`() {
        assertThat(ClusterJoinGuards.problems("a{cluster=~\"\$cluster\"} / on (host_name) b{cluster=~\"\$cluster\"}")).hasSize(1)
        assertThat(ClusterJoinGuards.problems("a / on(host_name) group_left(cassandra_build) b")).hasSize(1)
        assertThat(ClusterJoinGuards.problems("a / on (cluster, host_name) b")).isEmpty()
        assertThat(ClusterJoinGuards.problems("a and on (name) b")).isEmpty()
    }

    @Test
    fun `ignoring the cluster is reported, and ignoring another label is not`() {
        assertThat(ClusterJoinGuards.problems("a / ignoring(cluster, state) b")).hasSize(1)
        assertThat(ClusterJoinGuards.problems("a / ignoring(state) b")).isEmpty()
    }

    @Test
    fun `an aggregation by host that drops the cluster a host match needs is reported`() {
        val dropped = "sum by (host_name) (a) / on (cluster, host_name) sum by (cluster, host_name) (b)"

        assertThat(ClusterJoinGuards.problems(dropped)).containsExactly("by (host_name) drops the cluster a match needs")
        assertThat(ClusterJoinGuards.problems("sum by (cluster, host_name) (a) / on (cluster, host_name) b")).isEmpty()
        assertThat(ClusterJoinGuards.problems("sum by (host_name) (a)")).isEmpty()
    }

    @Test
    fun `dividing one metric by itself under another value of a label never pairs, unless the label is matched away`() {
        val size = """pd_cluster_status{cluster=~"${'$'}cluster", type="storage_size"}"""
        val capacity = """pd_cluster_status{cluster=~"${'$'}cluster", type="storage_capacity"}"""

        assertThat(ClusterJoinGuards.unmatchableSelectors("100 * $size / $capacity"))
            .containsExactly("""pd_cluster_status{type="storage_size"} / pd_cluster_status{type="storage_capacity"} never pair""")
        assertThat(ClusterJoinGuards.unmatchableSelectors("$size / ignoring (type) $capacity")).isEmpty()
        assertThat(ClusterJoinGuards.unmatchableSelectors("max by (cluster) ($size) / max by (cluster) ($capacity)")).isEmpty()
    }

    @Test
    fun `no dashboard divides one metric by itself under another label value`() {
        val problems =
            DashboardFiles.all().flatMap { file ->
                ClusterFilterGuards
                    .queries(Json.parseToJsonElement(file.readText()).jsonObject)
                    .filter { it.language == Language.PROMQL }
                    .flatMap { query -> ClusterJoinGuards.unmatchableSelectors(query.text).map { "${file.path} ${query.where}: $it" } }
            }

        assertThat(problems).isEmpty()
    }

    @Test
    fun `every dashboard matches hosts together with their cluster`() {
        val problems =
            DashboardFiles.all().flatMap { file ->
                ClusterFilterGuards
                    .queries(Json.parseToJsonElement(file.readText()).jsonObject)
                    .filter { it.language == Language.PROMQL }
                    .flatMap { query -> ClusterJoinGuards.problems(query.text).map { "${file.path} ${query.where}: $it" } }
                    .distinct()
            }

        assertThat(problems).isEmpty()
    }
}
