package com.rustyrazorblade.easydblab.export

import com.charleskorn.kaml.Yaml
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.configuration.ExperimentKey
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.export.query.DockerComposeCli
import com.rustyrazorblade.easydblab.export.query.DockerQueryRuntime
import com.rustyrazorblade.easydblab.export.query.QueryExporter
import com.rustyrazorblade.easydblab.export.query.QueryStoreAccess
import com.rustyrazorblade.easydblab.export.query.StackRuntime
import com.rustyrazorblade.easydblab.localstack.ComposeMount
import com.rustyrazorblade.easydblab.localstack.ComposeOverride
import com.rustyrazorblade.easydblab.localstack.ComposeProject
import com.rustyrazorblade.easydblab.localstack.ExternalNetwork
import com.rustyrazorblade.easydblab.localstack.NetworkMinio
import com.rustyrazorblade.easydblab.localstack.PackagedDashboardTree
import com.rustyrazorblade.easydblab.localstack.PackagedKitDashboards
import com.rustyrazorblade.easydblab.localstack.ServiceOverride
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.File
import java.net.ServerSocket
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile

/**
 * `observability export --cluster` and `--experiment` end to end (task 8.5): MinIO filled by the
 * cluster's own writers with four clusters in two tenants, the export run by the real [QueryExporter]
 * with its real temporary reader and writer stacks and thanos, and the zip opened with
 * `docker compose up` on an internal network.
 *
 * - **Nothing in S3 changes**: the listing, keys and ETags, is the same after both exports.
 * - **Only the selected clusters**: `--cluster qx-1 --cluster qw-4` holds their metrics, logs and
 *   traces and nothing of `qy-2` or `qz-3`, read back through the opened stack's own datasources; only
 *   their annotation backups and their experiments' reports.
 * - **Mixed tenants**: `qx-1` is in `acme` and `qw-4` in `default`; the home tenant is `acme`, and the
 *   opened stack has both tenants.
 * - **Same-named experiments stay apart**: `e1` is an experiment of both tenants; `--experiment e1
 *   --tenant acme` exports `qx-1` and `qy-2` and nothing of `default`.
 * - **Old spans**: `qx-1`'s span from three days ago is found by id and by search over its own time
 *   from the opened stack's Tempo, which holds only the zipped blocks, never the writer.
 * - **Structured metadata** survives: a filter on a line's metadata finds it in the opened stack.
 *
 * The reader's `compose.override.yml` (written as its stack starts) only makes the test's network the
 * signer's way out and points the signer at MinIO; the opened stack's makes its network internal. Both stacks are the
 * export's own.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class QueryExportIntegrationTest {
    private companion object {
        const val PROFILE = "query-export-it"
        const val ACME = "acme"
        const val DEFAULT = "default"
        const val LOG_TAIL = 4000
        val READY_WAIT: Duration = Duration.ofMinutes(5)
        val ANSWER_WAIT: Duration = Duration.ofMinutes(5)
        val POLL: Duration = Duration.ofSeconds(3)
        val X = QueryStoreSeed.Cluster("qx-1", ACME, "e1")
        val Y = QueryStoreSeed.Cluster("qy-2", ACME, "e1")
        val Z = QueryStoreSeed.Cluster("qz-3", ACME, "other")
        val W = QueryStoreSeed.Cluster("qw-4", DEFAULT, "e1")
    }

    /** One export run: its zip and its events. */
    private class Run(
        val zip: File,
        val events: List<Event>,
    ) {
        inline fun <reified T : Event> single(): T = events.filterIsInstance<T>().single()

        val data: List<String> by lazy {
            ZipFile(zip).use { file ->
                file
                    .entries()
                    .asSequence()
                    .map { it.name.substringAfter('/') }
                    .filter { it.startsWith("data/") }
                    .map { it.removePrefix("data/") }
                    .toList()
            }
        }
    }

    private lateinit var root: File
    private val suffix = UUID.randomUUID().toString().take(8)
    private val bucket = "edl-query-it-$suffix"
    private val networkName = "edl-query-it-$suffix"
    private lateinit var network: Network
    private lateinit var minio: NetworkMinio
    private lateinit var seeded: QueryStoreSeed.Seeded
    private lateinit var listingBefore: Map<String, String>
    private lateinit var clusters: Run
    private lateinit var experiment: Run
    private lateinit var stack: File
    private lateinit var project: ComposeProject

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @BeforeAll
    fun export(
        @TempDir tempDir: File,
    ) {
        root = tempDir
        network = Network.builder().createNetworkCmdModifier { it.withName(networkName) }.build()
        network.id
        minio = NetworkMinio(network)
        minio.createBucket(bucket)
        seeded = QueryStoreSeed(minio, bucket).seed(listOf(X, Y, Z, W), old = X)
        listOf(X, Z).forEach { putObject("grafana/annotations/$ACME/20261015-100000_${it.name}.json", "[${it.name}]") }
        putObject("grafana/annotations/$DEFAULT/20261015-100000_${W.name}.json", "[${W.name}]")
        putObject("reports/$ACME/e1/index.html", "acme e1")
        putObject("reports/$ACME/other/index.html", "acme other")
        putObject("reports/$DEFAULT/e1/index.html", "default e1")
        listingBefore = listing()

        clusters =
            run("clusters.zip") { exporter, target -> exporter.export(target, ExportSelector.Clusters(listOf(X.name, W.name)), null) }
        experiment =
            run(
                "experiment.zip",
            ) { exporter, target -> exporter.export(target, ExportSelector.Experiment(ExperimentKey(ACME, "e1")), null) }
    }

    @AfterAll
    fun cleanUp() {
        if (::project.isInitialized) project.compose("down", "-v", "--remove-orphans", "--timeout", "30")
        if (::root.isInitialized) removeRootOwnedState()
        if (::minio.isInitialized) minio.close()
        if (::network.isInitialized) network.close()
    }

    /** Runs one export with the real stacks, the reader's signer pointed at MinIO. */
    private fun run(
        name: String,
        export: (QueryExporter, ExportTarget) -> Unit,
    ): Run {
        val events = mutableListOf<Event>()
        val eventBus =
            EventBus().apply {
                addListener(
                    object : EventListener {
                        override fun onEvent(envelope: EventEnvelope) {
                            events += envelope.event
                        }

                        override fun close() = Unit
                    },
                )
            }
        val exporter =
            QueryExporter(
                stores = { QueryStoreAccess(S3ExportStore(minio.s3Client(), bucket), bucket, NetworkMinio.REGION, PROFILE) },
                runtime = DockerQueryRuntime(StackRuntime(DockerComposeCli, beforeStart = ::readerOverride)),
                stack =
                    ExportStackFiles(
                        PackagedDashboardTree(GrafanaDashboardCatalog.discover(), PackagedKitDashboards.discover()),
                        toolVersion = "it",
                        grafanaPort = freePort(),
                        documentsPort = freePort(),
                    ),
                zipWriter = ExportZipWriter(),
                eventBus = eventBus,
            )
        val zip = File(root, name)
        export(exporter, ExportTarget.of(zip))
        return Run(zip, events)
    }

    /** The reader's override: the test's network, and the signer signing for MinIO with the test's keys. */
    private fun readerOverride(directory: File) {
        if (directory.name != "reader") return
        val aws =
            File(root, "aws")
                .apply {
                    mkdirs()
                    File(this, "credentials").writeText(
                        "[$PROFILE]\naws_access_key_id = ${NetworkMinio.USER}\naws_secret_access_key = ${NetworkMinio.PASSWORD}\n",
                    )
                }
        val signer =
            listOf(
                "--name",
                "s3",
                "--region",
                NetworkMinio.REGION,
                "--host",
                minio.networkEndpoint,
                "--upstream-url-scheme",
                "http",
                "--port",
                ":${Constants.LocalStack.STORE_PORT}",
            )
        val override =
            ComposeOverride(
                // MinIO sits on the signer's own way out, which no other service of the reader joins.
                networks = mapOf(Constants.LocalStack.Networks.STORE_EGRESS to ExternalNetwork(networkName, external = true)),
                services =
                    mapOf(
                        Constants.LocalStack.Services.STORE_SIGNER to
                            ServiceOverride(
                                command = signer,
                                volumes =
                                    listOf(
                                        ComposeMount.bind(aws.absolutePath, "${Constants.LocalStack.SIGNER_HOME}/.aws", readOnly = true),
                                    ),
                            ),
                    ),
            )
        File(directory, Constants.LocalStack.OVERRIDE_FILE).writeText(override.toYaml())
    }

    private fun putObject(
        key: String,
        content: String,
    ) {
        minio.s3Client().use {
            it.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(key)
                    .build(),
                RequestBody.fromString(content),
            )
        }
    }

    /** Every object in the bucket, key to ETag, listed straight from MinIO. */
    private fun listing(): Map<String, String> =
        minio.s3Client().use { s3 ->
            s3
                .listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).build())
                .contents()
                .associate { it.key() to it.eTag() }
        }

    @Test
    @Order(1)
    fun `the exports write nothing to S3 - the bucket listing, keys and ETags, is unchanged`() {
        assertThat(clusters.zip).isFile()
        assertThat(experiment.zip).isFile()
        assertThat(listing()).isEqualTo(listingBefore)
    }

    @Test
    @Order(2)
    fun `a cluster export of two tenants holds both, with their annotations and experiments' reports only, and reports each signal`() {
        val selected = clusters.single<Event.Export.ClustersSelected>()
        assertThat(selected.home).isEqualTo(ACME)
        assertThat(selected.clusters.map { it.cluster to it.tenant }).containsExactly(X.name to ACME, W.name to DEFAULT)
        assertThat(clusters.data).anyMatch { it.startsWith("mimir/$ACME/") }.anyMatch { it.startsWith("mimir/$DEFAULT/") }
        assertThat(clusters.data).anyMatch { it.startsWith("tempo/$ACME/") }.anyMatch { it.startsWith("tempo/$DEFAULT/") }
        assertThat(clusters.data).contains(
            "grafana/annotations/$ACME/20261015-100000_${X.name}.json",
            "grafana/annotations/$DEFAULT/20261015-100000_${W.name}.json",
            "reports/$ACME/e1/index.html",
            "reports/$DEFAULT/e1/index.html",
        )
        assertThat(
            clusters.data,
        ).doesNotContain("grafana/annotations/$ACME/20261015-100000_${Z.name}.json", "reports/$ACME/other/index.html")
        val signals = clusters.events.filterIsInstance<Event.Export.SignalExported>().associateBy { it.signal }
        assertThat(signals.keys).containsExactlyInAnyOrder("metrics", "logs", "traces")
        assertThat(signals.values).allSatisfy { assertThat(it.items).isPositive() }
        assertThat(clusters.single<Event.Export.QueryCompleted>().tenants).containsExactly(ACME, DEFAULT)
        val kept = clusters.single<Event.Export.WorkDirectoryKept>()
        assertThat(File(kept.path)).isDirectory()
        assertThat(kept.bytes).isPositive()
    }

    @Test
    @Order(3)
    fun `an experiment of one tenant exports its clusters there and nothing of the same-named experiment of the other`() {
        assertThat(experiment.single<Event.Export.ClustersSelected>().clusters.map { it.cluster }).containsExactlyInAnyOrder(X.name, Y.name)
        assertThat(experiment.data).noneMatch { it.contains("/$DEFAULT/") || it.startsWith("loki/$DEFAULT") }
        assertThat(experiment.data).contains("reports/$ACME/e1/index.html").doesNotContain("reports/$ACME/other/index.html")
        assertThat(experiment.data).noneMatch { it.endsWith("_${Z.name}.json") }
        assertThat(experiment.single<Event.Export.QueryCompleted>().tenants).containsExactly(ACME)
    }

    @Test
    @Order(4)
    fun `the opened cluster export shows the selected clusters' metrics, logs with their metadata, and traces, and no other cluster's`() {
        openStack(clusters)

        awaitUntil(READY_WAIT, "Grafana answers", { project.logs(Constants.LocalStack.Services.GRAFANA).takeLast(LOG_TAIL) }) {
            grafana("/api/health")?.contains("\"database\"") == true
        }
        awaitUntil(ANSWER_WAIT, "both clusters' metrics", { project.logs(Constants.LocalStack.Services.MIMIR).takeLast(LOG_TAIL) }) {
            metricClusters() == setOf(X.name, W.name)
        }
        awaitUntil(ANSWER_WAIT, "both clusters' logs", { project.logs(Constants.LocalStack.Services.LOKI).takeLast(LOG_TAIL) }) {
            logClusters("{cluster=~\"q.+\"}") == setOf(X.name, W.name)
        }
        assertThat(logClusters("{cluster=~\"q.+\"} | probe_id=\"${seeded.probe.getValue(X.name)}\"")).containsExactly(X.name)
        awaitUntil(ANSWER_WAIT, "both clusters' traces", { project.logs(Constants.LocalStack.Services.TEMPO).takeLast(LOG_TAIL) }) {
            traceFound("tempo", seeded.traceIds.getValue(X.name)) && traceFound("tempo-$DEFAULT", seeded.traceIds.getValue(W.name))
        }
        assertThat(traceFound("tempo", seeded.traceIds.getValue(Y.name))).describedAs("an unselected cluster's trace").isFalse()
        assertThat(traceFound("tempo", seeded.traceIds.getValue(Z.name))).describedAs("an unselected cluster's trace").isFalse()
    }

    @Test
    @Order(5)
    fun `a days-old span is found by id and by search over its own time, from a Tempo that holds only the zipped blocks`() {
        val from = seeded.oldAt.minus(Duration.ofHours(1)).epochSecond
        val to = seeded.oldAt.plus(Duration.ofHours(1)).epochSecond
        awaitUntil(
            ANSWER_WAIT,
            "the old span by id over its own time",
            { project.logs(Constants.LocalStack.Services.TEMPO).takeLast(LOG_TAIL) },
        ) {
            traceFound("tempo", seeded.oldTraceId, "start=$from&end=$to")
        }
        val query = encode("{ resource.cluster = \"${X.name}\" }")
        awaitUntil(ANSWER_WAIT, "the old span by search over its own time", {
            project.logs(Constants.LocalStack.Services.TEMPO).takeLast(LOG_TAIL)
        }) {
            val body = grafana("/api/datasources/proxy/uid/tempo/api/search?q=$query&start=$from&end=$to") ?: return@awaitUntil false
            runCatching {
                Json
                    .parseToJsonElement(body)
                    .jsonObject
                    .getValue("traces")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("traceID")
                            .jsonPrimitive.content
                            .padStart(32, '0')
                    }
            }.getOrDefault(emptyList()).contains(seeded.oldTraceId)
        }
    }

    /** The project network, made internal: no container of the stack can reach anything outside it. */
    @Serializable
    private data class InternalNetwork(
        val internal: Boolean,
    )

    @Serializable
    private data class NetworkOverride(
        val networks: Map<String, InternalNetwork>,
    )

    private fun openStack(run: Run) {
        val parent = File(root, "opened").apply { mkdirs() }
        ZipFile(run.zip).use { file ->
            file.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                val target = File(parent, entry.name).apply { parentFile.mkdirs() }
                file.getInputStream(entry).use { input -> target.writeBytes(input.readBytes()) }
            }
        }
        stack = File(parent, run.zip.name.removeSuffix(".zip"))
        val override = NetworkOverride(mapOf("default" to InternalNetwork(internal = true)))
        File(stack, Constants.LocalStack.OVERRIDE_FILE).writeText(Yaml.default.encodeToString(NetworkOverride.serializer(), override))
        project = ComposeProject(stack, "edl-query-it-$suffix")
        project.composeOrFail("up", "-d")
    }

    /** A GET of Grafana's own API on loopback, from inside its container. Null when the request fails. */
    private fun grafana(path: String): String? {
        val result =
            project.compose(
                "exec",
                "-T",
                Constants.LocalStack.Services.GRAFANA,
                "wget",
                "-q",
                "-O",
                "-",
                "http://127.0.0.1:${Constants.K8s.GRAFANA_PORT}$path",
            )
        return result.output.takeIf { result.exitCode == 0 }
    }

    private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8)

    /** The clusters of the probe metric through the all-tenants metrics datasource. */
    private fun metricClusters(): Set<String>? {
        val query = encode("count by (cluster) (last_over_time(edl_query_probe[2h]))")
        val body = grafana("/api/datasources/proxy/uid/mimir--all/api/v1/query?query=$query") ?: return null
        return runCatching {
            Json
                .parseToJsonElement(body)
                .jsonObject
                .getValue("data")
                .jsonObject
                .getValue("result")
                .jsonArray
                .map {
                    it.jsonObject
                        .getValue("metric")
                        .jsonObject
                        .getValue("cluster")
                        .jsonPrimitive.content
                }.toSet()
        }.getOrNull()
    }

    /** The clusters of the lines [logql] finds over the last two hours through the all-tenants logs datasource. */
    private fun logClusters(logql: String): Set<String>? {
        val start = Instant.now().minus(Duration.ofHours(2)).epochSecond * 1_000_000_000L
        val body =
            grafana("/api/datasources/proxy/uid/loki--all/loki/api/v1/query_range?query=${encode(logql)}&start=$start&limit=1000")
                ?: return null
        return runCatching {
            Json
                .parseToJsonElement(body)
                .jsonObject
                .getValue("data")
                .jsonObject
                .getValue("result")
                .jsonArray
                .map {
                    it.jsonObject
                        .getValue("stream")
                        .jsonObject
                        .getValue("cluster")
                        .jsonPrimitive.content
                }.toSet()
        }.getOrNull()
    }

    private fun traceFound(
        datasource: String,
        traceId: String,
        window: String = "",
    ): Boolean {
        val body =
            grafana("/api/datasources/proxy/uid/$datasource/api/v2/traces/$traceId${if (window.isEmpty()) "" else "?$window"}")
                ?: return false
        return ObservabilityBackends.traceFound(200, body)
    }

    /** The backends of the reader and the opened stack run as root, so a container removes what they wrote under the test's directory. */
    private fun removeRootOwnedState() {
        GenericContainer(Constants.LocalStack.HEALTHCHECK_TOOLS_IMAGE)
            .withCreateContainerCmdModifier { it.hostConfig?.withBinds(Bind(root.absolutePath, Volume("/root-dir"))) }
            .withCommand("sh", "-c", "chmod -R a+rwX /root-dir")
            .withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(1)))
            .use { it.start() }
    }

    private fun awaitUntil(
        within: Duration,
        what: String,
        detail: () -> String,
        done: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + within.toNanos()
        while (!done() && System.nanoTime() < deadline) Thread.sleep(POLL.toMillis())
        assertThat(done()).describedAs { "$what within $within\n${detail()}" }.isTrue()
    }
}
