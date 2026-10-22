package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.DashboardQueries
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.PrometheusRemoteWrite
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorTaskDefinition
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.time.Instant
import java.util.UUID

/**
 * The shipped experiment pickers, run as Grafana runs them against `up` in the pinned Mimir.
 *
 * Tenant `default` holds experiment `e1` (clusters c1 and c2), an older experiment `e0` (cluster c0)
 * and a cluster from before the experiment label (`p`, no `experiment` or `edl_tenant`); tenant
 * `acme` holds its own experiment `e1` (cluster c3). Their last samples are minutes apart, so the
 * newest-first order is fixed: default/e1, default/<p>, acme/e1, default/e0.
 *
 * Each dashboard's `experiment`, `exp_tenant`, `exp_name` and `cluster` queries are read from the
 * file, filled in as Grafana fills them, run single-tenant (`default`) and federated
 * (`default|acme`) with `home_tenant` set to `default`, and turned into options with the variable's
 * own regex over Grafana's `query_result` rows (`{k="v",k2="v2"} <value> <ts>`). The Tests
 * dashboard's Experiments listing is run the same way.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExperimentPickerQueriesIntegrationTest {
    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "picker-it-${UUID.randomUUID().toString().take(8)}"
    private val volume = "picker-${UUID.randomUUID().toString().take(8)}"
    private lateinit var mimir: GenericContainer<*>
    private lateinit var base: String
    private val now: Instant = Instant.now()

    private val c0 = cluster("old_lab")
    private val c1 = cluster("lab_a")
    private val c2 = cluster("lab_b")
    private val c3 = cluster("acme_lab")
    private val p = cluster("prelabel")

    /** One seeded cluster: its labels and when its `up` samples end, in seconds before [now]. */
    private data class Seeded(
        val tenant: String,
        val labels: Map<String, String>,
        val endsSecondsAgo: Long,
    )

    private val seeded =
        listOf(
            // Written in this order, oldest first, so every sample of a tenant lands in order.
            Seeded(DEFAULT, labelled(c0, DEFAULT, "e0"), endsSecondsAgo = 590),
            Seeded(DEFAULT, mapOf("cluster" to p), endsSecondsAgo = 400),
            Seeded(DEFAULT, labelled(c1, DEFAULT, "e1"), endsSecondsAgo = 10),
            Seeded(DEFAULT, labelled(c2, DEFAULT, "e1"), endsSecondsAgo = 10),
            Seeded(ACME, labelled(c3, ACME, "e1"), endsSecondsAgo = 470),
        )

    /**
     * Tenant `pair`, experiment `ep`: two clusters that start 15s apart and end 15s apart, both inside
     * one 5-minute step of the pair pickers. The older is named to sort last, so a tie that falls back
     * to label order puts the wrong one first.
     */
    private val pairOld = cluster("zz_older")
    private val pairNew = cluster("aa_newer")

    /** The 5-minute step boundary the two pair clusters start after, about 20 minutes before [now]. */
    private val pairStep: Long = (now.epochSecond - PAIR_AGO_SECONDS) / PAIR_STEP_SECONDS * PAIR_STEP_SECONDS

    @BeforeAll
    fun startMimir() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        docker.createVolumeCmd().withName(volume).exec()
        val config = CompactorTaskDefinition.resource(MimirManifestBuilder::class.java, MimirManifestBuilder.CONFIG_FILE)
        mimir =
            ObservabilityBackends.startMimir(
                ObservabilityBackends.mimirConfig(config),
                volume,
                bucket,
                Constants.Observability.METRICS_ROOT,
            )
        base = ObservabilityBackends.baseUrl(mimir, Constants.K8s.MIMIR_HTTP_PORT)
        seeded.forEach { cluster ->
            val samples =
                (0 until SAMPLES).map { i ->
                    val at = now.minusSeconds(cluster.endsSecondsAgo + (SAMPLES - 1 - i) * STEP_SECONDS)
                    PrometheusRemoteWrite.Series(mapOf("__name__" to "up", "job" to "node") + cluster.labels, 1.0, at.toEpochMilli())
                }
            push(cluster.tenant, samples)
        }
        // Scraped every 15s: the older from 60s after the step boundary to 30s ago, the newer 15s later on each end.
        val pairEnd = now.epochSecond - PAIR_END_AGO_SECONDS
        push(
            PAIR,
            listOf(pairOld to pairStep + PAIR_START_OFFSET_SECONDS, pairNew to pairStep + PAIR_START_OFFSET_SECONDS + SCRAPE_SECONDS)
                .flatMap { (cluster, start) ->
                    val end = if (cluster == pairOld) pairEnd else pairEnd + SCRAPE_SECONDS
                    (start..end step SCRAPE_SECONDS).map { at ->
                        PrometheusRemoteWrite.Series(
                            mapOf("__name__" to "up", "job" to "node") + labelled(cluster, PAIR, "ep"),
                            1.0,
                            at * MILLIS_PER_SECOND,
                        )
                    }
                }.sortedBy { it.timestampMillis },
        )
    }

    @AfterAll
    fun stopMimir() {
        if (::mimir.isInitialized) mimir.stop()
        runCatching { docker.removeVolumeCmd(volume).exec() }
    }

    @ParameterizedTest
    @ValueSource(strings = [EXPERIMENT_SYSTEM, EXPERIMENT_CASSANDRA, TESTS])
    fun `single-tenant, the picker lists the tenant's experiments newest first, the pre-label cluster as its own`(path: String) {
        val picker = experimentOptions(path, DEFAULT)

        assertThat(picker.map { it.first })
            .containsExactlyElementsOf(listOf("default/e1", "default/$p", "default/e0") + allOption(path).map { it.first })
        assertThat(picker.map { it.second })
            .containsExactlyElementsOf(listOf("default/e1", "default/$p", "default/e0") + allOption(path).map { it.second })
    }

    @ParameterizedTest
    @ValueSource(strings = [EXPERIMENT_SYSTEM, EXPERIMENT_CASSANDRA, TESTS])
    fun `federated, the picker keeps the two tenants' e1 apart, newest first, the pre-label cluster in its own tenant`(path: String) {
        val picker = experimentOptions(path, FEDERATED)

        assertThat(picker.map { it.first })
            .containsExactlyElementsOf(listOf("default/e1", "default/$p", "acme/e1", "default/e0") + allOption(path).map { it.first })
    }

    @ParameterizedTest
    @ValueSource(strings = [EXPERIMENT_SYSTEM, EXPERIMENT_CASSANDRA, TESTS])
    fun `the cluster picker lists exactly the selected experiment's clusters, single-tenant and federated`(path: String) {
        for (tenant in listOf(DEFAULT, FEDERATED)) {
            assertThat(clusterOptions(path, tenant, "default/e1")).describedAs("$tenant default/e1").containsExactly(c1, c2)
            assertThat(clusterOptions(path, tenant, "default/$p")).describedAs("$tenant default/<p>").containsExactly(p)
            assertThat(clusterOptions(path, tenant, "default/e0")).describedAs("$tenant default/e0").containsExactly(c0)
        }
        assertThat(clusterOptions(path, FEDERATED, "acme/e1")).describedAs("federated acme/e1").containsExactly(c3)
    }

    @ParameterizedTest
    @ValueSource(strings = [DEFAULT, FEDERATED])
    fun `the Tests listing has one row per cluster, under its tenant and experiment, the pre-label cluster as its own experiment`(
        tenant: String,
    ) {
        val panel = panel(TESTS, "Experiments")
        val firstStart = rows(tenant, substitute(target(panel, "A"), mapOf("home_tenant" to DEFAULT)))
        val lastEnd = rows(tenant, substitute(target(panel, "B"), mapOf("home_tenant" to DEFAULT)))

        val expected =
            listOf(
                Triple(DEFAULT, "e0", c0),
                Triple(DEFAULT, "e1", c1),
                Triple(DEFAULT, "e1", c2),
                Triple(DEFAULT, p, p),
            ) + if (tenant == FEDERATED) listOf(Triple(ACME, "e1", c3)) else emptyList()
        for (listed in listOf(firstStart, lastEnd)) {
            assertThat(listed.map { Triple(it.labels["edl_tenant"], it.labels["experiment"], it.labels["cluster"]) })
                .containsExactlyInAnyOrderElementsOf(expected)
        }
        val newestFirst =
            lastEnd
                .groupBy { it.labels["edl_tenant"] + "/" + it.labels["experiment"] }
                .mapValues { (_, rows) -> rows.maxOf { it.value } }
                .entries
                .sortedByDescending { it.value }
                .map { it.key }
        val order = listOf("default/e1", "default/$p") + (if (tenant == FEDERATED) listOf("acme/e1") else emptyList()) + "default/e0"
        assertThat(newestFirst).containsExactlyElementsOf(order)
    }

    /**
     * Two clusters that started in the same 5-minute step tied on the step's timestamp, so the
     * default A (the first, oldest) fell to `sort()`'s tie order; the pickers resolve the real start
     * and end.
     */
    @ParameterizedTest
    @ValueSource(strings = [CLUSTER_PAIR, AB_COMPARISON, SYSTEM_AB_COMPARISON])
    fun `two clusters that start 15s apart in one 5-minute step list the older first in A, and the later-ending first in B`(path: String) {
        val selected = mapOf("exp_tenant" to PAIR, "exp_name" to "ep", "resolution" to PAIR_STEP_SECONDS.toString())

        assertThat(pairOptions(path, "cluster_a", selected)).containsExactly(pairOld, pairNew)
        assertThat(pairOptions(path, "cluster_b", selected)).containsExactly(pairNew, pairOld)
    }

    /** [name]'s values on [path] in tenant `pair`, with a 5-minute step. */
    private fun pairOptions(
        path: String,
        name: String,
        selected: Map<String, String>,
    ): List<String> = options(path, name, PAIR, selected).map { it.first }

    // ---------------------------------------------------------------- Grafana's variable flow

    /** The `experiment` picker's options, value to text, in the order Grafana keeps them (`sort: 0`). */
    private fun experimentOptions(
        path: String,
        tenant: String,
    ): List<Pair<String, String>> = options(path, "experiment", tenant, mapOf("home_tenant" to DEFAULT))

    /** The `cluster` picker's values once [experiment] is selected: the split, then the cluster query. */
    private fun clusterOptions(
        path: String,
        tenant: String,
        experiment: String,
    ): List<String> {
        val selected = mapOf("experiment" to experiment, "home_tenant" to DEFAULT)
        val split =
            listOf("exp_tenant", "exp_name").associateWith { name ->
                options(path, name, tenant, selected).single().first
            }
        return options(path, "cluster", tenant, selected + split).map { it.first }
    }

    /** [name]'s options on [path]: its `query_result` run in [tenant] with [selected] filled in, through its regex. */
    private fun options(
        path: String,
        name: String,
        tenant: String,
        selected: Map<String, String>,
    ): List<Pair<String, String>> {
        val variable = variable(path, name)
        val definition = variable.getValue("definition").jsonPrimitive.content
        assertThat(definition).describedAs("$path $name").startsWith("query_result(").endsWith(")")
        val expr = substitute(definition.removePrefix("query_result(").removeSuffix(")"), selected)
        val regex =
            Regex(
                variable
                    .getValue("regex")
                    .jsonPrimitive.content
                    .removePrefix("/")
                    .removeSuffix("/"),
            )
        val found =
            rows(tenant, expr)
                .map { it.text }
                .mapNotNull { row ->
                    regex.find(row)?.let { match ->
                        val value = match.groups["value"]!!.value
                        value to (runCatching { match.groups["text"]?.value }.getOrNull() ?: value)
                    }
                }.distinctBy { it.first }
        // Grafana's sort 1 is alphabetical by text; 0 keeps the query's order.
        val sort = variable["sort"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        return if (sort == SORT_ALPHABETICAL) found.sortedBy { it.second } else found
    }

    /** One instant-query result: its labels, its value, and the row text Grafana's `query_result` builds. */
    private data class Row(
        val labels: Map<String, String>,
        val value: Double,
        val text: String,
    )

    /** [expr] as an instant query at [now] in [tenant], each result as Grafana's `query_result` writes it. */
    private fun rows(
        tenant: String,
        expr: String,
    ): List<Row> {
        val url = "$base/prometheus/api/v1/query?query=${URLEncoder.encode(expr, Charsets.UTF_8)}&time=${now.epochSecond}"
        val response = ObservabilityBackends.get(url, tenant)
        assertThat(response.statusCode()).describedAs("$expr\n${response.body()}").isEqualTo(HTTP_OK)
        return Json
            .parseToJsonElement(response.body())
            .jsonObject
            .getValue("data")
            .jsonObject
            .getValue("result")
            .jsonArray
            .map { it.jsonObject }
            .map { result ->
                val labels = result.getValue("metric").jsonObject.mapValues { it.value.jsonPrimitive.content }
                val (ts, value) = result.getValue("value").jsonArray.map { it.jsonPrimitive.content }
                val text =
                    (labels["__name__"] ?: "") +
                        labels.filterKeys { it != "__name__" }.entries.joinToString(",", "{", "}") { "${it.key}=\"${it.value}\"" } +
                        " $value ${(ts.toDouble() * MILLIS_PER_SECOND).toLong()}"
                Row(labels, value.toDouble(), text)
            }
    }

    /** [text] filled in as Grafana fills it for these pickers: the test's lookback and step, and [selected]. */
    private fun substitute(
        text: String,
        selected: Map<String, String>,
    ): String = DashboardQueries.substitute(text, mapOf("lookback" to LOOKBACK, "resolution" to RESOLUTION_SECONDS) + selected)

    /** The experiment dashboards end their picker with a synthetic "All"; Tests selects one experiment. */
    private fun allOption(path: String): List<Pair<String, String>> = if (path == TESTS) emptyList() else listOf(".*/.*" to "All")

    // ---------------------------------------------------------------- the dashboard files

    private fun dashboard(path: String): JsonObject = Json.parseToJsonElement(File(path).readText()).jsonObject

    private fun variable(
        path: String,
        name: String,
    ): JsonObject =
        dashboard(path)
            .getValue("templating")
            .jsonObject
            .getValue("list")
            .jsonArray
            .map { it.jsonObject }
            .single { it["name"]?.jsonPrimitive?.contentOrNull == name }

    private fun panel(
        path: String,
        title: String,
    ): JsonObject {
        val found = mutableListOf<JsonObject>()

        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    if (element["title"]?.jsonPrimitive?.contentOrNull == title && "targets" in element) found += element
                    element.values.forEach(::walk)
                }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(dashboard(path).getValue("panels"))
        return found.single()
    }

    private fun target(
        panel: JsonObject,
        refId: String,
    ): String =
        panel
            .getValue("targets")
            .jsonArray
            .map { it.jsonObject }
            .single { it["refId"]?.jsonPrimitive?.contentOrNull == refId }
            .getValue("expr")
            .jsonPrimitive
            .content

    // ---------------------------------------------------------------- seeding

    private fun cluster(name: String): String = "$name-${UUID.randomUUID()}"

    private fun labelled(
        cluster: String,
        tenant: String,
        experiment: String,
    ): Map<String, String> = mapOf("cluster" to cluster, "experiment" to experiment, "edl_tenant" to tenant)

    private fun push(
        tenant: String,
        series: List<PrometheusRemoteWrite.Series>,
    ) {
        val response =
            ObservabilityBackends.send(
                HttpRequest
                    .newBuilder(URI("$base/api/v1/push"))
                    .header("Content-Type", "application/x-protobuf")
                    .header("Content-Encoding", "snappy")
                    .header("X-Prometheus-Remote-Write-Version", "0.1.0")
                    .header(Constants.Observability.TENANT_HEADER, tenant)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(PrometheusRemoteWrite.body(series)))
                    .build(),
            )
        assertThat(response.statusCode()).describedAs("push to $tenant: ${response.body()}").isBetween(HTTP_OK, HTTP_LAST_SUCCESS)
    }

    private companion object {
        const val DEFAULT = "default"
        const val ACME = "acme"
        const val FEDERATED = "default|acme"
        const val EXPERIMENT_SYSTEM = "dashboards/infrastructure/experiment-system.json"
        const val EXPERIMENT_CASSANDRA = "dashboards/cassandra/experiment-cassandra.json"
        const val TESTS = "dashboards/infrastructure/tests.json"
        const val CLUSTER_PAIR = "dashboards/cassandra/cluster-pair.json"
        const val AB_COMPARISON = "dashboards/cassandra/ab-comparison.json"
        const val SYSTEM_AB_COMPARISON = "dashboards/infrastructure/system-ab-comparison.json"
        const val PAIR = "pair"

        /** The pair pickers' default step, 5 minutes. */
        const val PAIR_STEP_SECONDS = 300L

        /** About how long before now the two pair clusters start. */
        const val PAIR_AGO_SECONDS = 1200L

        /** How far after the step boundary the older pair cluster's first sample lands. */
        const val PAIR_START_OFFSET_SECONDS = 60L

        /** How long before now the older pair cluster's last sample lands. */
        const val PAIR_END_AGO_SECONDS = 30L

        /** The pair clusters' scrape interval, and how far apart they start and end. */
        const val SCRAPE_SECONDS = 15L

        /** The pickers' `lookback`, longer than every seeded sample's age. */
        const val LOOKBACK = "1h"

        /** The pickers' `resolution`, the subquery step: finer than the gaps between the seeded clusters' last samples. */
        const val RESOLUTION_SECONDS = "60"
        const val SAMPLES = 3
        const val STEP_SECONDS = 10L
        const val MILLIS_PER_SECOND = 1000
        const val HTTP_OK = 200
        const val HTTP_LAST_SUCCESS = 299
        const val SORT_ALPHABETICAL = 1
    }
}
