package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Enforces that every packaged dashboard can tell two clusters apart.
 *
 * Metrics, logs and profiles from many clusters land in one store so clusters can be compared on a
 * single panel. A dashboard with no cluster field, or a query with no cluster filter, renders two
 * clusters as one series and nothing about the result looks broken.
 *
 * The walk covers `labelSelector` fields as well as `expr` fields. A test that walked only `expr`
 * passed `profiling.json` — whose filters are all Pyroscope `labelSelector`s — while it silently
 * blended, which is also why an earlier survey reported that dashboard as "0 of 0 queries".
 *
 * Dashboards are read from the packaged resources rather than the source tree, so this checks what
 * actually ships.
 */
class DashboardClusterScopeTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Fields holding a datasource query, in every datasource dialect the dashboards use. */
    private val queryFields = setOf("expr", "labelSelector")

    private val resourceRoot: File =
        File(
            requireNotNull(javaClass.getResource("/system-overview.json")) {
                "Dashboard resources are not on the classpath; run ./gradlew processResources"
            }.toURI(),
        ).parentFile

    /** Every packaged dashboard: the core ones at the resource root, plus every kit's own. */
    private fun packagedDashboards(): List<File> {
        val core = resourceRoot.listFiles { file -> file.extension == "json" }.orEmpty().toList()
        val kits =
            File(resourceRoot, "com/rustyrazorblade/easydblab/kits")
                .walkTopDown()
                .filter { it.extension == "json" && it.parentFile.name == "dashboards" }
                .toList()
        return (core + kits).sortedBy { it.name }
    }

    private fun describe(file: File): String = file.toRelativeString(resourceRoot)

    private fun clusterVariable(dashboard: JsonObject): JsonObject? =
        (dashboard["templating"]?.jsonObject?.get("list") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["name"] as? JsonPrimitive)?.content == "cluster" }

    /** Yields every query string in the dashboard, paired with the field it came from. */
    private fun queries(element: JsonElement): Sequence<Pair<String, String>> =
        when (element) {
            is JsonObject ->
                element.entries.asSequence().flatMap { (key, value) ->
                    val literal = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (key in queryFields && literal != null) {
                        sequenceOf(key to literal)
                    } else {
                        queries(value)
                    }
                }
            is JsonArray -> element.asSequence().flatMap { queries(it) }
            else -> emptySequence()
        }

    // `${cluster:regex}` is the same variable with an explicit format: Grafana only interpolates a
    // multi-value variable as a regex alternation on its own for Prometheus-family datasources.
    private fun isClusterScoped(query: String): Boolean = query.contains("\$cluster") || query.contains("\${cluster")

    @Test
    fun `every packaged dashboard exposes a cluster multi-select variable`() {
        val offenders =
            packagedDashboards().mapNotNull { file ->
                val variable = clusterVariable(json.parseToJsonElement(file.readText()).jsonObject)
                when {
                    variable == null -> "${describe(file)}: no cluster variable"
                    (variable["multi"] as? JsonPrimitive)?.boolean != true ->
                        "${describe(file)}: cluster variable is not multi-select"
                    (variable["includeAll"] as? JsonPrimitive)?.boolean != true ->
                        "${describe(file)}: cluster variable has no All option"
                    else -> null
                }
            }

        assertThat(offenders)
            .`as`("dashboards missing a cluster multi-select variable")
            .isEmpty()
    }

    @Test
    fun `every dashboard query is scoped by the cluster variable`() {
        val offenders =
            packagedDashboards().flatMap { file ->
                queries(json.parseToJsonElement(file.readText()))
                    .filter { (_, query) -> query.isNotBlank() }
                    .filterNot { (_, query) -> isClusterScoped(query) }
                    .map { (field, query) -> "${describe(file)} [$field]: $query" }
                    .toList()
            }

        assertThat(offenders)
            .`as`("unscoped dashboard queries — two clusters would blend into one series")
            .isEmpty()
    }

    @Test
    fun `a single-cluster deployment needs no manual selection`() {
        // With one cluster in the store the dashboards must look exactly as they did before this
        // filter existed. That holds because the variable defaults to All, and All is a regex that
        // matches the one cluster present rather than an empty selection.
        val offenders =
            packagedDashboards().mapNotNull { file ->
                val variable =
                    requireNotNull(clusterVariable(json.parseToJsonElement(file.readText()).jsonObject)) {
                        "${describe(file)} has no cluster variable"
                    }
                val allValue = (variable["allValue"] as? JsonPrimitive)?.content
                if (allValue.isNullOrBlank()) "${describe(file)}: cluster variable has no allValue" else null
            }

        assertThat(offenders)
            .`as`("cluster variables whose All option would select nothing")
            .isEmpty()
    }

    @Test
    fun `the walk reaches Pyroscope labelSelector fields, not only expr fields`() {
        // Without this the previous test would pass a Pyroscope-only dashboard that carries no
        // cluster filter at all, because such a dashboard has no `expr` field to inspect.
        val pyroscopeOnly =
            json.parseToJsonElement(
                """
                {"panels":[{"targets":[{"labelSelector":"{service_name=\"x\"}"}]}]}
                """.trimIndent(),
            )

        assertThat(queries(pyroscopeOnly).toList())
            .containsExactly("labelSelector" to """{service_name="x"}""")
    }
}
