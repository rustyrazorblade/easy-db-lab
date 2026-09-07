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
 * Metrics, logs, traces and profiles from many clusters land in one store so clusters can be
 * compared on a single panel. A dashboard with no cluster field, or a query with no cluster filter,
 * renders two clusters as one series and nothing about the result looks broken.
 *
 * A cluster filter can live in five places, and the walk reaches all five, because each one has
 * silently blended in the past:
 * - `expr` — PromQL and VictoriaLogs LogsQL.
 * - `labelSelector` — Pyroscope. A walk that read only `expr` passed `profiling.json`, whose
 *   filters are all `labelSelector`s, which is also why an earlier survey reported that dashboard
 *   as "0 of 0 queries".
 * - `query` and `serviceMapQuery` inside a `targets` entry — Tempo. Read only through `targets`,
 *   because a template variable's own `query` object is a different thing entirely.
 * - a dataLink `url` into Explore — the TraceQL, LogsQL or Pyroscope query rides inside the
 *   URL-encoded `panes=` payload, so clicking through from a cluster-A panel opened every
 *   cluster's traces.
 * - `templating.list[]` — an unfiltered dropdown lists values from every cluster, and picking one
 *   that exists only in another cluster gives an empty panel with no explanation.
 *
 * A blank query is an offender, not a skip. A blank `labelSelector` or `serviceMapQuery` means
 * *match everything*, which is the exact blend this test exists to catch.
 *
 * Dashboards are read from the packaged resources rather than the source tree, so this checks what
 * actually ships.
 */
class DashboardClusterScopeTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Query fields that mean the same thing wherever they appear. */
    private val queryFields = setOf("expr", "labelSelector")

    /** Tempo's query fields, which are only a query when they sit in a `targets` entry. */
    private val targetQueryFields = setOf("query", "serviceMapQuery")

    /** Every string in a template variable that could carry its filter. */
    private val variableQueryFields = setOf("query", "labelSelector", "definition")

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

    private fun stringOrNull(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * Yields every query string in the dashboard, paired with the field it came from.
     *
     * @param insideTarget true while walking a `targets` entry, where Tempo's `query` and
     *   `serviceMapQuery` are datasource queries rather than ordinary object keys.
     */
    private fun queries(
        element: JsonElement,
        insideTarget: Boolean = false,
    ): Sequence<Pair<String, String>> =
        when (element) {
            is JsonObject ->
                element.entries.asSequence().flatMap { (key, value) ->
                    fieldQueries(key, value, insideTarget)
                }
            is JsonArray -> element.asSequence().flatMap { queries(it, insideTarget) }
            else -> emptySequence()
        }

    private fun fieldQueries(
        key: String,
        value: JsonElement,
        insideTarget: Boolean,
    ): Sequence<Pair<String, String>> {
        val literal = stringOrNull(value)
        return when {
            key == "templating" -> templatingQueries(value)
            key == "targets" && value is JsonArray -> value.asSequence().flatMap { queries(it, insideTarget = true) }
            // A drill-through into Explore carries its whole query inside the URL-encoded payload.
            key == "url" && literal != null && literal.contains("/explore?") -> sequenceOf("dataLink url" to literal)
            key in queryFields && literal != null -> sequenceOf(key to literal)
            insideTarget && key in targetQueryFields && literal != null -> sequenceOf(key to literal)
            else -> queries(value, insideTarget)
        }
    }

    /**
     * Yields one entry per datasource-backed template variable, holding every string that could
     * carry its cluster filter.
     *
     * The variable is the unit that is or is not scoped, not the individual field: Pyroscope keeps
     * the filter in `query.labelSelector` while `definition` stays a human-readable label, and
     * Prometheus keeps it in `query.query` with `definition` mirroring it. Joining them means the
     * filter counts wherever the datasource actually reads it.
     *
     * The cluster picker itself is exempt — a list of clusters cannot be filtered by the selected
     * cluster. ClickHouse declares a second one, `KeeperCluster`, hence the suffix match.
     */
    private fun templatingQueries(templating: JsonElement): Sequence<Pair<String, String>> {
        val variables = (templating as? JsonObject)?.get("list") as? JsonArray ?: return emptySequence()
        return variables
            .asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { stringOrNull(it["type"]) == "query" }
            .filterNot { isClusterPicker(stringOrNull(it["name"]).orEmpty()) }
            .map { variable ->
                val name = stringOrNull(variable["name"]).orEmpty()
                val nested = variable["query"] as? JsonObject
                val strings =
                    buildList {
                        addAll(variableQueryFields.mapNotNull { stringOrNull(variable[it]) })
                        addAll(variableQueryFields.mapNotNull { stringOrNull(nested?.get(it)) })
                    }
                "templating[$name]" to strings.joinToString(" | ")
            }
    }

    /** True for a variable that enumerates clusters, whatever the dashboard chose to call it. */
    private fun isClusterPicker(name: String): Boolean = name.endsWith("cluster", ignoreCase = true)

    // `${cluster:regex}` is the same variable with an explicit format: Grafana only interpolates a
    // multi-value variable as a regex alternation on its own for Prometheus-family datasources.
    // The suffix match also accepts a dashboard's second cluster picker, e.g. `$KeeperCluster`.
    private val clusterReference = Regex("""\$\{?[A-Za-z_]*[Cc]luster\b""")

    private fun isClusterScoped(query: String): Boolean = clusterReference.containsMatchIn(query)

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
                    .filterNot { (_, query) -> isClusterScoped(query) }
                    .map { (field, query) -> "${describe(file)} [$field]: $query" }
                    .distinct()
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
    fun `the walk reaches every datasource dialect, not only expr fields`() {
        // Each of these is a place a cluster filter has actually gone missing. Narrowing the walk
        // back to a subset of them fails here rather than passing the dashboards silently.
        val everyDialect =
            json.parseToJsonElement(
                """
                {
                  "templating": {"list": [
                    {"name": "service", "type": "query", "query": {"query": "label_values(up, job)"}}
                  ]},
                  "panels": [
                    {"targets": [{"expr": "up"}]},
                    {"targets": [{"labelSelector": "{service_name=\"x\"}"}]},
                    {"targets": [
                      {"datasource": {"type": "tempo"}, "queryType": "traceql", "query": "{ name = \"x\" }"},
                      {"datasource": {"type": "tempo"}, "queryType": "serviceMap", "serviceMapQuery": "{}"}
                    ]},
                    {"fieldConfig": {"defaults": {"links": [
                      {"title": "Traces", "url": "/explore?panes=%7B%22query%22%3A%22%7B%7D%22%7D"}
                    ]}}}
                  ]
                }
                """.trimIndent(),
            )

        assertThat(queries(everyDialect).toList())
            .containsExactlyInAnyOrder(
                "templating[service]" to "label_values(up, job)",
                "expr" to "up",
                "labelSelector" to """{service_name="x"}""",
                "query" to """{ name = "x" }""",
                "serviceMapQuery" to "{}",
                "dataLink url" to "/explore?panes=%7B%22query%22%3A%22%7B%7D%22%7D",
            )
    }

    @Test
    fun `a template variable's own query object is not mistaken for a Tempo target`() {
        // `query` outside a `targets` entry is a variable definition, not a datasource query.
        // Reading it as one turns every `label_values(...)` wrapper into a phantom offender.
        val variableOnly =
            json.parseToJsonElement(
                """
                {"templating": {"list": [
                  {"name": "cluster", "type": "query", "query": {"query": "label_values(up, cluster)"}},
                  {"name": "datasource", "type": "datasource", "query": "prometheus"},
                  {"name": "mode", "type": "custom", "query": "read,write"}
                ]}}
                """.trimIndent(),
            )

        // The cluster picker is exempt, and neither a datasource nor a custom variable queries
        // anything — so nothing here is a query at all.
        assertThat(queries(variableOnly).toList()).isEmpty()
    }
}
