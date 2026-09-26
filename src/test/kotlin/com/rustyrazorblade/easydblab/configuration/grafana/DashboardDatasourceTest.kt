package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every datasource a dashboard names must be one Grafana is provisioned with. A panel whose
 * datasource uid does not exist renders "datasource not found" rather than an empty graph, and
 * nothing else in the build reads the dashboard JSON, so a rename that misses one file would ship.
 */
class DashboardDatasourceTest {
    private val provisioned =
        GrafanaDatasourceConfig
            .create("t")
            .datasources
            .mapNotNull { it.uid }
            .toSet()

    /** Grafana's own datasources, which every instance has. */
    private val builtIn = setOf("-- Grafana --", "grafana", "-- Mixed --", "-- Dashboard --")

    /** The core dashboards and every kit's dashboards. */
    private fun dashboards(): List<File> {
        val core = File("dashboards").walkTopDown().filter { it.isFile && it.extension == "json" }
        val kits =
            File("src/main/resources/com/rustyrazorblade/easydblab/kits")
                .walkTopDown()
                .filter { it.isFile && it.extension == "json" && it.parentFile.name == "dashboards" }
        return (core + kits).toList()
    }

    /** Every datasource reference in [element]: a uid, or the old bare-string form. */
    private fun datasourceRefs(element: JsonElement): List<String> =
        when (element) {
            is JsonObject ->
                element.flatMap { (key, value) ->
                    val here =
                        when {
                            key != "datasource" -> emptyList()
                            value is JsonPrimitive -> listOfNotNull(value.contentOrNull)
                            value is JsonObject -> listOfNotNull((value["uid"] as? JsonPrimitive)?.contentOrNull)
                            else -> emptyList()
                        }
                    here + datasourceRefs(value)
                }
            is JsonArray -> element.flatMap { datasourceRefs(it) }
            else -> emptyList()
        }

    @Test
    fun `the provisioned datasources are the four backends`() {
        assertThat(provisioned).containsExactlyInAnyOrder(
            Constants.Grafana.DatasourceUid.MIMIR,
            Constants.Grafana.DatasourceUid.LOKI,
            Constants.Grafana.DatasourceUid.TEMPO,
            Constants.Grafana.DatasourceUid.PYROSCOPE,
        )
    }

    @Test
    fun `every dashboard names only provisioned datasources`() {
        val files = dashboards()
        assertThat(files).isNotEmpty()

        val unknown =
            files.flatMap { file ->
                datasourceRefs(Json.parseToJsonElement(file.readText()))
                    .filterNot { it in provisioned || it in builtIn || it.startsWith("$") }
                    .map { "${file.path}: $it" }
            }
        assertThat(unknown).isEmpty()
    }
}
