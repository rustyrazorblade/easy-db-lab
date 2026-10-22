package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every `cluster` variable is multi-select with "All" on `${metrics_datasource}`, and lists one of
 * two sets:
 *
 * - on a dashboard with no `experiment` variable, every cluster of the tenant the Metrics picker
 *   selects, with `label_values(up, cluster)`;
 * - on a dashboard that declares `experiment`, only the selected experiment's clusters, filtered by
 *   `edl_tenant=~"$exp_tenant"` and `experiment=~"$exp_name"` and joined with the branch for the
 *   clusters from before the label. Its "All" carries no `allValue`, so it means the experiment's
 *   clusters and not every cluster.
 *
 * The Tests dashboard selects one experiment in place of one cluster, listed over `lookback`, as
 * its listing is, so a 24-hour default range still offers every experiment.
 */
class ClusterVariableTest {
    private val clusterQuery = "label_values(up, cluster)"

    private fun parse(file: File): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject

    private fun declaresExperiment(dashboard: JsonObject): Boolean = variables(dashboard).any { it.string("name") == "experiment" }

    private fun variables(dashboard: JsonObject): List<JsonObject> =
        (
            dashboard["templating"]
                ?.jsonObject
                ?.get("list")
                ?.jsonArray
                .orEmpty()
        ).map { it.jsonObject }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** The query a Prometheus query variable runs: the editor object's `query`, or the legacy string. */
    private fun queryOf(variable: JsonObject): String? =
        when (val query = variable["query"]) {
            is JsonObject -> query.string("query")
            is JsonPrimitive -> query.contentOrNull
            else -> null
        }

    private fun clusterVariables(): List<Pair<String, Pair<JsonObject, JsonObject>>> =
        DashboardFiles.all().flatMap { file ->
            val dashboard = parse(file)
            variables(dashboard).filter { it.string("name") == "cluster" }.map { file.path to (dashboard to it) }
        }

    @Test
    fun `every cluster variable outside an experiment dashboard reads the metrics picker with label_values of up`() {
        val clusters = clusterVariables().filterNot { (_, pair) -> declaresExperiment(pair.first) }
        assertThat(clusters).isNotEmpty()

        val wrong =
            clusters.mapNotNull { (path, pair) ->
                val variable = pair.second
                val datasource = variable["datasource"]?.jsonObject?.string("uid")
                val query = queryOf(variable)
                "$path: query=$query datasource=$datasource".takeUnless {
                    query == clusterQuery &&
                        datasource == "\${metrics_datasource}" &&
                        (variable.string("definition") ?: clusterQuery) == clusterQuery
                }
            }
        assertThat(wrong).isEmpty()
    }

    /** What is wrong with [variable] as the `cluster` list of a dashboard that declares `experiment`. */
    private fun experimentClusterProblems(variable: JsonObject): List<String> {
        val query = queryOf(variable).orEmpty()
        return listOfNotNull(
            "reads ${variable["datasource"]?.jsonObject?.string("uid")}, not \${metrics_datasource}"
                .takeUnless { variable["datasource"]?.jsonObject?.string("uid") == "\${metrics_datasource}" },
            "does not list the experiment's clusters with $EXPERIMENT_CLUSTERS and $PRE_LABEL_CLUSTERS"
                .takeUnless { EXPERIMENT_CLUSTERS in query && PRE_LABEL_CLUSTERS in query && " or " in query },
            "its definition differs from its query".takeUnless { (variable.string("definition") ?: query) == query },
            "its All has an allValue, so it is not the experiment's clusters".takeUnless { variable.string("allValue").isNullOrEmpty() },
        )
    }

    @Test
    fun `an experiment cluster list that reads every cluster, or All past the experiment, is reported`() {
        val good =
            buildJsonObject {
                put("name", "cluster")
                put("type", "query")
                put("datasource", buildJsonObject { put("uid", "\${metrics_datasource}") })
                put(
                    "query",
                    buildJsonObject {
                        put(
                            "query",
                            "query_result(max by (cluster) (count by (cluster) (up{$EXPERIMENT_CLUSTERS}) " +
                                "or count by (cluster) (up{$PRE_LABEL_CLUSTERS})))",
                        )
                    },
                )
            }

        assertThat(experimentClusterProblems(good)).isEmpty()
        assertThat(experimentClusterProblems(JsonObject(good + ("allValue" to JsonPrimitive(".*")))))
            .containsExactly("its All has an allValue, so it is not the experiment's clusters")
        assertThat(experimentClusterProblems(JsonObject(good + ("query" to JsonPrimitive(clusterQuery)))))
            .containsExactly("does not list the experiment's clusters with $EXPERIMENT_CLUSTERS and $PRE_LABEL_CLUSTERS")
    }

    @Test
    fun `every cluster variable on an experiment dashboard lists the experiment's clusters`() {
        val wrong =
            clusterVariables()
                .filter { (_, pair) -> declaresExperiment(pair.first) }
                .flatMap { (path, pair) -> experimentClusterProblems(pair.second).map { "$path: $it" } }

        assertThat(wrong).isEmpty()
    }

    @Test
    fun `every cluster variable is multi-select with All`() {
        val wrong =
            clusterVariables()
                .mapNotNull { (path, pair) ->
                    val variable = pair.second
                    "$path: multi=${variable.string("multi")} includeAll=${variable.string("includeAll")}".takeUnless {
                        variable.string("multi") == "true" && variable.string("includeAll") == "true"
                    }
                }
        assertThat(wrong).isEmpty()
    }

    /**
     * The Tests dashboard opens on the last 24 hours: at 180 days Grafana sent the markers annotation
     * as 180 one-day Loki requests in a row. Its listing and its `experiment` variable both read over
     * `lookback`, so the short range still lists and offers every experiment. A single-select
     * `experiment` replaces the single-select `cluster`.
     */
    @Test
    fun `the Tests dashboard opens on 24 hours and selects one experiment from those within lookback`() {
        val tests = parse(File("dashboards/infrastructure/tests.json"))
        val experiment = variables(tests).firstOrNull { it.string("name") == "experiment" }
        val singleCluster = variables(tests).filter { it.string("name") == "cluster" && it.string("multi") != "true" }

        assertThat(experiment).describedAs("Tests declares experiment").isNotNull()
        assertThat(experiment?.string("multi")).isEqualTo("false")
        assertThat(experiment?.string("includeAll")).isEqualTo("false")
        assertThat(experiment?.get("datasource")?.jsonObject?.string("uid")).isEqualTo("\${metrics_datasource}")
        assertThat(experiment?.let { queryOf(it) }).contains("[\$lookback:")
        assertThat(singleCluster).describedAs("Tests no longer selects one cluster").isEmpty()
        assertThat(tests.getValue("time").jsonObject.string("from")).isEqualTo("now-24h")
        assertThat(tests.getValue("time").jsonObject.string("to")).isEqualTo("now")
    }

    /** Every string in [element], at any depth. */
    private fun strings(element: JsonElement): List<String> =
        when (element) {
            is JsonObject -> element.values.flatMap { strings(it) }
            is JsonArray -> element.flatMap { strings(it) }
            is JsonPrimitive -> listOfNotNull(element.contentOrNull.takeIf { element.isString })
        }

    /**
     * A multi-select `cluster` renders as a regex (`(a|b)`, or the All value), which an equality
     * matcher never matches: `cluster="\$cluster"` goes empty as soon as a second cluster is picked.
     */
    @Test
    fun `no query on a dashboard with a multi-select cluster matches it by equality`() {
        val equality = Regex("""cluster\s*=\s*"\$\{?cluster\b""")
        val wrong =
            DashboardFiles.all().flatMap { file ->
                val dashboard = parse(file)
                val multi = variables(dashboard).any { it.string("name") == "cluster" && it.string("multi") == "true" }
                if (!multi) emptyList() else strings(dashboard).filter { equality.containsMatchIn(it) }.map { "${file.path}: $it" }
            }
        assertThat(wrong).isEmpty()
    }

    private companion object {
        /** The labelled series of the selected experiment, in its own tenant. */
        const val EXPERIMENT_CLUSTERS = "edl_tenant=~\"\$exp_tenant\", experiment=~\"\$exp_name\""

        /** A cluster from before the label is the experiment of one named for it. */
        const val PRE_LABEL_CLUSTERS = "experiment=\"\", cluster=~\"\$exp_name\""
    }
}
