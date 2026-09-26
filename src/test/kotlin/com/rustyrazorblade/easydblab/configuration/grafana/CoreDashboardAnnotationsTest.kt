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
 */
class CoreDashboardAnnotationsTest {
    private val coreDashboards =
        listOf(
            "cassandra/ab-comparison.json",
            "cassandra/cassandra-overview.json",
            "cassandra/cluster-comparison.json",
            "cassandra/node-divergence.json",
            "infrastructure/system-ab-comparison.json",
            "infrastructure/system-overview.json",
        )

    private fun markers(path: String): JsonObject =
        Json
            .parseToJsonElement(File("dashboards/$path").readText())
            .jsonObject
            .getValue("annotations")
            .jsonObject
            .getValue("list")
            .jsonArray
            .map { it.jsonObject }
            .single { it["name"]?.jsonPrimitive?.content == "easy-db-lab markers" }

    @Test
    fun `every core dashboard reads global annotations from Loki`() {
        for (path in coreDashboards) {
            val query = markers(path)
            val expr = query["expr"]?.jsonPrimitive?.content.orEmpty()

            assertThat(
                query
                    .getValue("datasource")
                    .jsonObject["uid"]
                    ?.jsonPrimitive
                    ?.content,
            ).describedAs(path).isEqualTo("loki")
            assertThat(expr).describedAs(path).contains("source=\"annotation\"", "cluster=~", "| dashboard_uid=\"\"")
            assertThat(query).describedAs(path).doesNotContainKey("target")
        }
    }
}
