package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The core dashboards render global annotations (no dashboard scope) from Loki, where the annotation
 * mirror writes every Grafana annotation. Grafana's built-in query keeps rendering this cluster's
 * dashboard-scoped annotations, so the Loki query takes only the global ones: each renders once.
 * The Tests markers show the annotations of the selected experiment's clusters, which its
 * `cluster` variable lists.
 */
class CoreDashboardAnnotationsTest {
    private val coreDashboards =
        listOf(
            "cassandra/ab-comparison.json",
            "cassandra/cassandra-overview.json",
            "cassandra/cluster-pair.json",
            "cassandra/experiment-cassandra.json",
            "cassandra/node-divergence.json",
            "infrastructure/experiment-system.json",
            "infrastructure/system-ab-comparison.json",
            "infrastructure/system-overview.json",
            "infrastructure/tests.json",
        )

    @Test
    fun `the Tests markers show the selected experiment's clusters`() {
        val expr = markers("infrastructure/tests.json")["expr"]?.jsonPrimitive?.content.orEmpty()

        assertThat(expr).contains("cluster=~\"\${cluster:regex}\"").doesNotContain("experiment", "edl_tenant")
    }

    private fun markers(path: String): JsonObject =
        File("dashboards/$path")
            .also { assertThat(it).describedAs(path).exists() }
            .let { Json.parseToJsonElement(it.readText()) }
            .jsonObject
            .getValue("annotations")
            .jsonObject
            .getValue("list")
            .jsonArray
            .map { it.jsonObject }
            .single { it["name"]?.jsonPrimitive?.content == "easy-db-lab markers" }

    @Test
    fun `every core dashboard reads global annotations from the logs picker`() {
        assertThat(coreDashboards.filterNot { File("dashboards/$it").exists() }).describedAs("missing dashboards").isEmpty()
        for (path in coreDashboards) {
            val query = markers(path)
            val expr = query["expr"]?.jsonPrimitive?.content.orEmpty()

            assertThat(query.getValue("datasource"))
                .describedAs(path)
                .isEqualTo(Json.parseToJsonElement("""{"type":"loki","uid":"${'$'}{logs_datasource}"}"""))
            assertThat(expr).describedAs(path).contains("source=\"annotation\"", "cluster=~", "| dashboard_uid=\"\"")
            assertThat(query).describedAs(path).doesNotContainKey("target")
        }
    }
}
