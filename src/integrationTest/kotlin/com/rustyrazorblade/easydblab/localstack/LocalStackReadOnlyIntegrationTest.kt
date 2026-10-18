package com.rustyrazorblade.easydblab.localstack

import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.grafana.BackendUrls
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDatasourceSet
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.time.Duration
import java.util.UUID

/**
 * The live local stack, as `local create` writes it, run with `docker compose up` against a store in
 * MinIO that the cluster's own writers filled for two tenants, `default` and `acme`.
 *
 * The test's `compose.override.yml` changes only what a laptop test needs: the signer's way out is the
 * test's Docker network (where MinIO is), the signer proxies to MinIO over HTTP instead of
 * `s3.<region>.amazonaws.com` with a `~/.aws` holding a static MinIO profile, and the backends' and the
 * gate's ports are published for the test. Everything else, every image, flag, config file, mount,
 * dependency and healthcheck, is the generated stack's.
 *
 * Scenarios:
 * - **The bucket listing is unchanged.** Keys and ETags are listed before the stack starts and after
 *   metrics, logs and traces queries of each tenant and of both tenants federated. Every request the
 *   gate refused is Tempo's `PUT tempo/<tenant>/index.pb.zst` (no `work.json`, no DELETE, no POST),
 *   and no block is merged or marked compacted. The backends sign with placeholder keys, so every
 *   answer also proves the signer re-signed their requests for MinIO.
 * - **The gate refuses every write.** A PUT, a POST and a DELETE sent to the gate get 403 and change
 *   nothing; a GET passes and is signed by the signer.
 * - **The signer shows credentials.** The generated healthcheck (the static busybox `wget`, mounted
 *   into the scratch signer image) reports healthy with good credentials, and unhealthy with a
 *   profile whose secret MinIO rejects; the store check exits 1 naming the bucket and the profile.
 * - **Local Grafana has a cluster's datasources** for `default` and `acme`, and accepts the
 *   provisioning reloads as the server admin.
 * - **`local dashboards --from` loads an edit** into the running Grafana with no container recreated.
 * - **Tempo reads a block through memcached.** Repeated fetches of one trace stop reading its block's
 *   `data.parquet` through the gate.
 *
 * A trace counts as found only when `trace.resourceSpans` holds spans: Tempo answers
 * `200 {"trace":{}}` for a trace it does not have.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LocalStackReadOnlyIntegrationTest {
    private companion object {
        const val HOME = "default"
        const val OTHER = "acme"
        val TENANTS = listOf(OTHER, HOME)
        const val PROFILE = "edl-it"
        const val BAD_SIGNER = "store-signer-bad"
        const val BAD_CHECK = "store-check-bad"
        const val PROBE_DASHBOARD = "edl-local-it-probe"
        val READY_WAIT: Duration = Duration.ofMinutes(5)
        val ANSWER_WAIT: Duration = Duration.ofMinutes(4)

        /** The generated signer healthcheck runs every 60s after a 10s start period and needs one pass. */
        val HEALTH_WAIT: Duration = Duration.ofMinutes(4)
        val POLL: Duration = Duration.ofSeconds(3)
        const val BAD_HEALTH_INTERVAL = "5s"
        const val HTTP_OK = 200
        const val HTTP_FORBIDDEN = 403
        const val PROBE_TIMEOUT_SECONDS = "5"

        /** Fetches of one trace in a row, and how many of the first may still read the block while Tempo fills its cache. */
        const val REPEATED_FETCHES = 10
        const val CACHE_FILL_FETCHES = 3
        val GATE_LOG_SETTLE: Duration = Duration.ofMillis(500)

        /** What Mimir and Tempo write when a block is merged away or marked: deletion, no-compact, compacted. */
        val COMPACTION_MARKS = listOf("deletion-mark.json", "no-compact-mark.json", "meta.compacted.json")
        val REFUSED_LINE = Regex("""^REFUSED \S+ \S+ (\S+) "([^"]*)" (\d+)$""")
    }

    /** The class's temporary directory, given to [startStack]: a field is not injected before a per-class `@BeforeAll`. */
    private lateinit var root: File

    private val suffix = UUID.randomUUID().toString().take(8)
    private val bucket = "edl-local-it-$suffix"
    private val networkName = "edl-local-it-$suffix"
    private lateinit var network: Network
    private lateinit var minio: NetworkMinio
    private lateinit var seeded: StoreSeed.Seeded
    private lateinit var stack: File
    private lateinit var project: ComposeProject
    private lateinit var manifest: LocalStackManifest
    private lateinit var listingBefore: Map<String, String>
    private val grafana = LocalGrafanaHttp()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @BeforeAll
    fun startStack(
        @TempDir tempDir: File,
    ) {
        root = tempDir
        network = Network.builder().createNetworkCmdModifier { it.withName(networkName) }.build()
        network.id
        minio = NetworkMinio(network)
        minio.createBucket(bucket)
        seeded = StoreSeed(minio, bucket).seed(TENANTS)

        stack = File(root, "lab-local")
        // MinIO has one endpoint for every region: the bucket's region still picks the client.
        val store = SdkLocalStackStore(minio.s3Client(), { minio.s3Client() }, EventBus())
        val region = (store.check(bucket) as StoreReadResult.Readable).region
        val spec =
            LocalStackSpec(
                mode = LocalStackSpec.Mode.Live(bucket = bucket, region = region, awsProfile = PROFILE, home = HOME),
                // Only the home tenant at first: the re-run test finds acme and loads it into the running Grafana.
                tenants = listOf(HOME),
                grafanaPort = freePort(),
                documentsPort = freePort(),
            )
        manifest = LocalStackWriter("it").write(stack, spec, LocalStackComposer.compose(spec)).manifest
        LocalStackDashboards(grafana, PackagedDashboardTree(GrafanaDashboardCatalog.discover(), PackagedKitDashboards.discover()))
            .install(stack, manifest, source = null, reload = false)
        File(stack, Constants.LocalStack.OVERRIDE_FILE).writeText(override().toYaml())

        project = ComposeProject(stack, "edl-local-it-$suffix")
        listingBefore = listing()
        project.composeOrFail("up", "-d")
        awaitReady()
    }

    @AfterAll
    fun stopStack() {
        if (::project.isInitialized) project.compose("down", "-v", "--remove-orphans", "--timeout", "30")
        if (::stack.isInitialized) removeRootOwnedState()
        if (::minio.isInitialized) minio.close()
        if (::network.isInitialized) network.close()
    }

    /** The test's override: see the class KDoc for what it changes and why. */
    private fun override(): ComposeOverride {
        fun awsDir(
            name: String,
            secret: String,
        ): String =
            File(root, name)
                .apply {
                    mkdirs()
                    File(this, "credentials").writeText(
                        "[$PROFILE]\naws_access_key_id = ${NetworkMinio.USER}\naws_secret_access_key = $secret\n",
                    )
                }.absolutePath

        val signerCommand =
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
        val awsMount = "${Constants.LocalStack.SIGNER_HOME}/.aws"
        val generated = ComposeFile.fromYaml(File(stack, Constants.LocalStack.COMPOSE_FILE).readText())
        val signerHealth = requireNotNull(generated.services.getValue(Constants.LocalStack.Services.STORE_SIGNER).healthcheck)

        fun published(port: Int) = listOf("${Constants.LocalStack.LOOPBACK}::$port")
        return ComposeOverride(
            // MinIO sits on the signer's own way out, which no other service of the stack joins.
            networks = mapOf(Constants.LocalStack.Networks.STORE_EGRESS to ExternalNetwork(networkName, external = true)),
            services =
                mapOf(
                    Constants.LocalStack.Services.STORE_SIGNER to
                        ServiceOverride(
                            command = signerCommand,
                            volumes = listOf(ComposeMount.bind(awsDir("aws-good", NetworkMinio.PASSWORD), awsMount, readOnly = true)),
                        ),
                    Constants.LocalStack.Services.STORE_GATE to ServiceOverride(ports = published(Constants.LocalStack.STORE_PORT)),
                    Constants.LocalStack.Services.MIMIR to ServiceOverride(ports = published(Constants.K8s.MIMIR_HTTP_PORT)),
                    Constants.LocalStack.Services.LOKI to ServiceOverride(ports = published(Constants.K8s.LOKI_HTTP_PORT)),
                    Constants.LocalStack.Services.TEMPO to ServiceOverride(ports = published(Constants.K8s.TEMPO_PORT)),
                    BAD_SIGNER to
                        ServiceOverride(
                            extends = ComposeExtends(Constants.LocalStack.COMPOSE_FILE, Constants.LocalStack.Services.STORE_SIGNER),
                            command = signerCommand,
                            volumes = listOf(ComposeMount.bind(awsDir("aws-bad", "not-the-secret"), awsMount, readOnly = true)),
                            healthcheck = signerHealth.copy(interval = BAD_HEALTH_INTERVAL, startPeriod = "1s"),
                        ),
                    BAD_CHECK to
                        ServiceOverride(
                            extends = ComposeExtends(Constants.LocalStack.COMPOSE_FILE, Constants.LocalStack.Services.STORE_CHECK),
                            environment = mapOf("SIGNER" to "$BAD_SIGNER:${Constants.LocalStack.STORE_PORT}"),
                            dependsOn = mapOf(BAD_SIGNER to ComposeDependency.STARTED),
                        ),
                ),
        )
    }

    /** Waits until each backend and Grafana is ready on its published port. */
    private fun awaitReady() {
        val backends =
            mapOf(
                Constants.LocalStack.Services.MIMIR to Constants.K8s.MIMIR_HTTP_PORT,
                Constants.LocalStack.Services.LOKI to Constants.K8s.LOKI_HTTP_PORT,
                Constants.LocalStack.Services.TEMPO to Constants.K8s.TEMPO_PORT,
            )
        backends.forEach { (service, port) ->
            val url = "http://${Constants.LocalStack.LOOPBACK}:${awaitPort(service, port)}/ready"
            awaitUntil(READY_WAIT, "$service ready", { project.logs(service).takeLast(LOG_TAIL) }) {
                runCatching { ObservabilityBackends.get(url, HOME).statusCode() == HTTP_OK }.getOrDefault(false)
            }
        }
        awaitUntil(READY_WAIT, "Grafana ready", { project.logs(Constants.LocalStack.Services.GRAFANA).takeLast(LOG_TAIL) }) {
            grafana.isRunning(manifest.grafanaPort)
        }
    }

    private fun awaitPort(
        service: String,
        port: Int,
    ): Int {
        var published: Int? = null
        awaitUntil(READY_WAIT, "$service published", { project.compose("ps", "-a").output }) {
            published = runCatching { project.port(service, port) }.getOrNull()
            published != null
        }
        return requireNotNull(published)
    }

    private fun backend(
        service: String,
        port: Int,
    ): String = "http://${Constants.LocalStack.LOOPBACK}:${project.port(service, port)}"

    /** Every object in the bucket, key to ETag, listed straight from MinIO. */
    private fun listing(): Map<String, String> =
        minio.s3Client().use { s3 ->
            s3
                .listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).build())
                .contents()
                .associate { it.key() to it.eTag() }
        }

    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    /** The `cluster` label of every series of the probe metric [tenant] (or `a|b`) answers, with `__tenant_id__` when federated. */
    private fun metricSeries(tenant: String): List<Map<String, String>> {
        // The samples are minutes old by now, past the instant query's lookback.
        val query = URLEncoder.encode("last_over_time(${seeded.metric}[1h])", Charsets.UTF_8)
        val answer =
            ObservabilityBackends.get(
                "${backend(Constants.LocalStack.Services.MIMIR, Constants.K8s.MIMIR_HTTP_PORT)}/prometheus/api/v1/query?query=$query",
                tenant,
            )
        if (answer.statusCode() != HTTP_OK) return emptyList()
        return json(answer.body())
            .getValue("data")
            .jsonObject
            .getValue("result")
            .jsonArray
            .map { series ->
                series.jsonObject
                    .getValue("metric")
                    .jsonObject
                    .mapValues { it.value.jsonPrimitive.content }
            }
    }

    /** The clusters of the log lines [tenant] (or `a|b`) answers over the last hour, each as many times as it has lines. */
    private fun logClusters(tenant: String): List<String> {
        val query = URLEncoder.encode("""{cluster=~"c-.+"}""", Charsets.UTF_8)
        val answer =
            ObservabilityBackends.get(
                "${backend(
                    Constants.LocalStack.Services.LOKI,
                    Constants.K8s.LOKI_HTTP_PORT,
                )}/loki/api/v1/query_range?query=$query&since=1h&limit=1000",
                tenant,
            )
        if (answer.statusCode() != HTTP_OK) return emptyList()
        return json(answer.body())
            .getValue("data")
            .jsonObject
            .getValue("result")
            .jsonArray
            .flatMap { stream ->
                val cluster =
                    stream.jsonObject
                        .getValue("stream")
                        .jsonObject
                        .getValue("cluster")
                        .jsonPrimitive.content
                List(
                    stream.jsonObject
                        .getValue("values")
                        .jsonArray.size,
                ) { cluster }
            }
    }

    private fun traceFound(
        tenant: String,
        traceId: String,
    ): Boolean {
        val answer =
            ObservabilityBackends.get(
                "${backend(Constants.LocalStack.Services.TEMPO, Constants.K8s.TEMPO_PORT)}/api/v2/traces/$traceId",
                tenant,
            )
        return ObservabilityBackends.traceFound(answer.statusCode(), answer.body())
    }

    /** The trace ids a TraceQL search for the probe spans finds for [tenant] (or `a|b`) over the last hour. */
    private fun searchedTraces(tenant: String): Set<String> {
        val query = URLEncoder.encode("""{ resource.service.name = "local-stack-it" }""", Charsets.UTF_8)
        val end = System.currentTimeMillis() / 1000
        val answer =
            ObservabilityBackends.get(
                "${backend(
                    Constants.LocalStack.Services.TEMPO,
                    Constants.K8s.TEMPO_PORT,
                )}/api/search?q=$query&start=${end - 3600}&end=$end",
                tenant,
            )
        if (answer.statusCode() != HTTP_OK) return emptySet()
        return json(answer.body())["traces"]
            ?.jsonArray
            ?.map {
                it.jsonObject
                    .getValue("traceID")
                    .jsonPrimitive.content
                    .padStart(32, '0')
            }?.toSet()
            .orEmpty()
    }

    private fun refusedRequests(): List<MatchResult> =
        project
            .logs(Constants.LocalStack.Services.STORE_GATE)
            .lines()
            .filter { it.startsWith("REFUSED") }
            .map { line -> requireNotNull(REFUSED_LINE.matchEntire(line.trim())) { "unexpected gate line: $line" } }

    @Test
    @Order(1)
    fun `every tenant's metrics, logs and traces are served, single-tenant and federated, and the bucket listing is unchanged`() {
        val federated = TENANTS.joinToString("|")
        TENANTS.forEach { tenant ->
            val cluster = seeded.cluster(tenant)
            awaitUntil(ANSWER_WAIT, "metrics of $tenant", { project.logs(Constants.LocalStack.Services.MIMIR).takeLast(LOG_TAIL) }) {
                metricSeries(tenant).map { it["cluster"] } == listOf(cluster)
            }
            awaitUntil(ANSWER_WAIT, "logs of $tenant", { project.logs(Constants.LocalStack.Services.LOKI).takeLast(LOG_TAIL) }) {
                logClusters(tenant) == List(seeded.linesPerCluster) { cluster }
            }
            awaitUntil(ANSWER_WAIT, "the trace of $tenant", { project.logs(Constants.LocalStack.Services.TEMPO).takeLast(LOG_TAIL) }) {
                traceFound(tenant, seeded.traceIds.getValue(tenant))
            }
        }
        assertThat(metricSeries(federated).map { it["__tenant_id__"] to it["cluster"] })
            .containsExactlyInAnyOrder(*TENANTS.map { it to seeded.cluster(it) }.toTypedArray())
        assertThat(logClusters(federated)).containsExactlyInAnyOrderElementsOf(
            TENANTS.flatMap { tenant -> List(seeded.linesPerCluster) { seeded.cluster(tenant) } },
        )
        seeded.traceIds.values.forEach { traceId ->
            assertThat(traceFound(federated, traceId)).describedAs("trace $traceId found federated").isTrue()
        }
        awaitUntil(ANSWER_WAIT, "a federated trace search finding both tenants' traces", { searchedTraces(federated).toString() }) {
            searchedTraces(federated) == seeded.traceIds.values.toSet()
        }

        val listingAfter = listing()
        assertThat(listingAfter).describedAs("bucket listing, key to ETag").isEqualTo(listingBefore)

        // The seeded store has the account compactor's marks; the stack adds and moves none.
        fun marks(listing: Map<String, String>) = listing.filter { (key, _) -> COMPACTION_MARKS.any { key.endsWith(it) } }
        assertThat(marks(listingAfter)).describedAs("blocks merged, or marked for deletion or as compacted").isEqualTo(marks(listingBefore))

        val refused = refusedRequests()
        assertThat(refused).describedAs("Tempo tried to write its tenant index, and the gate refused it").isNotEmpty()
        assertThat(refused).allSatisfy { request ->
            val (method, uri, status) = request.destructured
            assertThat(method).isEqualTo("PUT")
            assertThat(uri.substringBefore('?')).matches("/$bucket/tempo/(${TENANTS.joinToString("|")})/index\\.pb\\.zst")
            assertThat(status.toInt()).isEqualTo(HTTP_FORBIDDEN)
        }
    }

    @Test
    @Order(2)
    fun `the gate refuses a PUT, a POST and a DELETE and passes a GET, which the signer signs`() {
        val gate = backend(Constants.LocalStack.Services.STORE_GATE, Constants.LocalStack.STORE_PORT)
        val existing = listingBefore.keys.first { it.startsWith("${Constants.Observability.METRICS_ROOT}/") }
        val requests =
            mapOf(
                "PUT" to HttpRequest.newBuilder(URI("$gate/$bucket/gate-probe/put")).PUT(HttpRequest.BodyPublishers.ofString("x")),
                "POST" to HttpRequest.newBuilder(URI("$gate/$bucket?delete")).POST(HttpRequest.BodyPublishers.ofString("<Delete/>")),
                "DELETE" to HttpRequest.newBuilder(URI("$gate/$bucket/$existing")).DELETE(),
            )

        requests.forEach { (method, request) ->
            assertThat(ObservabilityBackends.send(request.build()).statusCode()).describedAs(method).isEqualTo(HTTP_FORBIDDEN)
        }
        val list = ObservabilityBackends.send(HttpRequest.newBuilder(URI("$gate/$bucket?list-type=2&prefix=mimir%2F")).GET().build())

        assertThat(list.statusCode()).describedAs(list.body()).isEqualTo(HTTP_OK)
        assertThat(list.body()).contains("<Key>$existing</Key>")
        assertThat(listing()).isEqualTo(listingBefore)
    }

    @Test
    @Order(3)
    fun `the signer is healthy with good credentials and unhealthy with bad ones, and the store check names the failure`() {
        fun health(service: String) = project.inspect(service, "{{.State.Health.Status}}")
        awaitUntil(
            HEALTH_WAIT,
            "the signer healthy",
            { project.inspect(Constants.LocalStack.Services.STORE_SIGNER, "{{json .State.Health}}") },
        ) {
            health(Constants.LocalStack.Services.STORE_SIGNER) == "healthy"
        }
        awaitUntil(HEALTH_WAIT, "the bad signer unhealthy", { project.inspect(BAD_SIGNER, "{{json .State.Health}}") }) {
            health(BAD_SIGNER) == "unhealthy"
        }
        assertThat(project.inspect(Constants.LocalStack.Services.STORE_CHECK, "{{.State.ExitCode}}")).isEqualTo("0")

        awaitUntil(HEALTH_WAIT, "the bad store check exited", { project.logs(BAD_CHECK) }) {
            project.inspect(BAD_CHECK, "{{.State.Status}}") == "exited"
        }
        assertThat(project.inspect(BAD_CHECK, "{{.State.ExitCode}}")).isEqualTo("1")
        assertThat(project.logs(BAD_CHECK))
            .contains("cannot read the account bucket $bucket with profile $PROFILE")
            .contains("(HTTP $HTTP_FORBIDDEN)")
    }

    /** The uids of the datasources the running Grafana has now. */
    private fun datasourceUids(): List<String> {
        val answer = ObservabilityBackends.get("${grafanaBase()}/api/datasources", HOME)
        assertThat(answer.statusCode()).describedAs(answer.body()).isEqualTo(HTTP_OK)
        return Json.parseToJsonElement(answer.body()).jsonArray.map {
            it.jsonObject
                .getValue("uid")
                .jsonPrimitive.content
        }
    }

    /**
     * The stack was written with only the home tenant. A re-run of `local create` with the stack up
     * lists acme, rewrites the datasource file inside the mounted provisioning directory, and the
     * running Grafana reloads it, then its dashboards: acme's datasources show with no restart.
     */
    @Test
    @Order(4)
    fun `a re-run of local create with the stack up loads a new tenant into the running Grafana with no restart`() {
        val services = ComposeFile.fromYaml(File(stack, Constants.LocalStack.COMPOSE_FILE).readText()).services.keys
        val before = project.containerIds(services)
        assertThat(datasourceUids()).doesNotContain("mimir-acme")
        val events = mutableListOf<Event>()
        val eventBus = EventBus().apply { addListener(CollectingListener(events)) }
        val user =
            User(
                email = "",
                region = NetworkMinio.REGION,
                keyName = "",
                awsProfile = PROFILE,
                awsAccessKey = "",
                awsSecret = "",
                s3Bucket = bucket,
            )
        val creator =
            LocalStackCreator(
                user = user,
                store = SdkLocalStackStore(minio.s3Client(), { minio.s3Client() }, EventBus()),
                writer = LocalStackWriter("it"),
                grafana = grafana,
                dashboards =
                    LocalStackDashboards(
                        grafana,
                        PackagedDashboardTree(GrafanaDashboardCatalog.discover(), PackagedKitDashboards.discover()),
                    ),
                eventBus = eventBus,
            )

        creator.create(stack, HOME)

        assertThat(events.filterIsInstance<Event.LocalStack.ReloadRefused>()).isEmpty()
        assertThat(events.filterIsInstance<Event.LocalStack.DatasourcesReloaded>()).hasSize(1)
        assertThat(events.filterIsInstance<Event.LocalStack.DashboardsInstalled>().single().reloaded).isTrue()
        assertThat(datasourceUids()).contains("mimir-acme", "loki-acme", "tempo-acme")
        assertThat(project.containerIds(services)).isEqualTo(before)
        assertThat(listing()).isEqualTo(listingBefore)
    }

    /** Collects every event into [events]. */
    private class CollectingListener(
        private val events: MutableList<Event>,
    ) : EventListener {
        override fun onEvent(envelope: EventEnvelope) {
            events += envelope.event
        }

        override fun close() = Unit
    }

    @Test
    @Order(5)
    fun `local Grafana has a cluster's datasource uids for default and acme, and reloads them as the server admin`() {
        val clusterUids =
            GrafanaDatasourceSet
                .build(TenantSet.of(HOME, listOf(OTHER)), BackendUrls.CONTROL_NODE)
                .datasources
                .mapNotNull { it.uid }

        grafana.reloadDatasources(manifest.grafanaPort)
        val answer = ObservabilityBackends.get("${grafanaBase()}/api/datasources", HOME)

        assertThat(answer.statusCode()).describedAs(answer.body()).isEqualTo(HTTP_OK)
        val localUids =
            Json.parseToJsonElement(answer.body()).jsonArray.map {
                it.jsonObject
                    .getValue("uid")
                    .jsonPrimitive.content
            }
        assertThat(localUids).containsExactlyInAnyOrderElementsOf(clusterUids)
        assertThat(localUids).contains("mimir-acme", "mimir-default", "mimir--all", "loki-acme", "loki--all", "tempo-acme", "tempo--all")
    }

    @Test
    @Order(6)
    fun `local dashboards --from loads an edit into the running Grafana with no container recreated`() {
        val services = ComposeFile.fromYaml(File(stack, Constants.LocalStack.COMPOSE_FILE).readText()).services.keys
        val before = project.containerIds(services)
        val homeUid = homeDashboardUid()
        awaitUntil(ANSWER_WAIT, "the packaged home dashboard in Grafana", { "" }) { dashboardStatus(homeUid) == HTTP_OK }
        val work =
            File(root, "work").apply {
                File(this, "mine/probe.json")
                    .apply { parentFile.mkdirs() }
                    .writeText("""{"uid":"$PROBE_DASHBOARD","title":"Local stack probe","panels":[],"schemaVersion":39}""")
            }

        LocalDashboardsRefresher(
            grafana,
            LocalStackDashboards(grafana, PackagedDashboardTree(GrafanaDashboardCatalog.discover(), emptyList())),
            EventBus(),
        ).refresh(stack, work)

        awaitUntil(ANSWER_WAIT, "the edited dashboard in Grafana", { "" }) { dashboardStatus(PROBE_DASHBOARD) == HTTP_OK }
        assertThat(project.containerIds(services)).isEqualTo(before)
        assertThat(listing()).isEqualTo(listingBefore)
    }

    /**
     * `wget` of [url] from inside [service]'s network namespace: a one-off busybox joins the running
     * container's network, so it resolves and reaches exactly what [service] does.
     */
    private fun probeFrom(
        service: String,
        url: String,
    ): ComposeProject.Result =
        project.docker(
            "run",
            "--rm",
            "--network",
            "container:${project.containerId(service)}",
            Constants.LocalStack.HEALTHCHECK_TOOLS_IMAGE,
            "wget",
            "-q",
            "-O",
            "-",
            "-T",
            PROBE_TIMEOUT_SECONDS,
            url,
        )

    /**
     * The signer signs any method with the operator's credentials, and Grafana runs anonymous Admin: a
     * datasource pointed at the signer would bypass the gate. From inside Grafana, a backend and the
     * documents view, the signer is unreachable by name and by its address, while the gate answers a
     * read; and Grafana's data source proxy refuses a datasource that names anything but the stack's
     * backends, even the gate. The stack still answers queries afterwards.
     */
    @Test
    @Order(7)
    fun `no container but the gate and the store check reaches the signer, and Grafana proxies only to the backends`() {
        val signerAddress =
            project.inspect(
                Constants.LocalStack.Services.STORE_SIGNER,
                "{{(index .NetworkSettings.Networks \"${project.name}_${Constants.LocalStack.Networks.STORE_ACCESS}\").IPAddress}}",
            )
        assertThat(signerAddress).describedAs("the signer's address on the store-access network").isNotBlank()
        val list = "/$bucket?list-type=2&prefix=mimir%2F&max-keys=1"
        val port = Constants.LocalStack.STORE_PORT
        for (service in listOf(
            Constants.LocalStack.Services.GRAFANA,
            Constants.LocalStack.Services.MIMIR,
            Constants.LocalStack.Services.DOCUMENTS,
        )) {
            val byName = probeFrom(service, "http://${Constants.LocalStack.Services.STORE_SIGNER}:$port$list")
            val byAddress = probeFrom(service, "http://$signerAddress:$port$list")
            val gate = probeFrom(service, "http://${Constants.LocalStack.Services.STORE_GATE}:$port$list")

            assertThat(byName.exitCode).describedAs("$service reaching the signer by name: ${byName.output}").isNotEqualTo(0)
            assertThat(
                byAddress.exitCode,
            ).describedAs("$service reaching the signer at $signerAddress: ${byAddress.output}").isNotEqualTo(0)
            assertThat(gate.exitCode).describedAs("$service reading through the gate: ${gate.output}").isEqualTo(0)
            assertThat(gate.output).describedAs(service).contains("<Key>mimir/")
        }

        val gateDatasource = "edl-it-gate-probe"
        val created =
            ObservabilityBackends.send(
                HttpRequest
                    .newBuilder(URI("${grafanaBase()}/api/datasources"))
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            """{"name":"$gateDatasource","uid":"$gateDatasource","type":"prometheus","access":"proxy",""" +
                                """"url":"http://${Constants.LocalStack.Services.STORE_GATE}:$port"}""",
                        ),
                    ).build(),
            )
        assertThat(created.statusCode()).describedAs(created.body()).isEqualTo(HTTP_OK)
        try {
            val proxied = ObservabilityBackends.get("${grafanaBase()}/api/datasources/proxy/uid/$gateDatasource$list", HOME)
            assertThat(proxied.statusCode()).describedAs(proxied.body()).isEqualTo(HTTP_FORBIDDEN)
            assertThat(proxied.body()).doesNotContain("<Key>")
        } finally {
            ObservabilityBackends.send(HttpRequest.newBuilder(URI("${grafanaBase()}/api/datasources/uid/$gateDatasource")).DELETE().build())
        }

        awaitUntil(ANSWER_WAIT, "metrics of $HOME after the probes", { "" }) {
            metricSeries(HOME).map { it["cluster"] } == listOf(seeded.cluster(HOME))
        }
        assertThat(listing()).isEqualTo(listingBefore)
    }

    /** How many reads of a Tempo block's `data.parquet` the gate has passed so far. */
    private fun blockReads(): Int =
        project
            .logs(Constants.LocalStack.Services.STORE_GATE)
            .lines()
            .count { line ->
                line.contains(" GET ") &&
                    line.contains("/${Constants.Observability.TRACES_ROOT}/") &&
                    line.contains("/data.parquet")
            }

    /**
     * A trace-by-id fetch reads every page of the block that holds the trace. Through the stack's
     * memcached, repeated fetches from one block stop reading it from the store: Tempo stores what it
     * read in the background, so the first few fetches may still read, and then none does. Without the
     * cache every fetch read the whole block again.
     */
    @Test
    @Order(8)
    fun `repeated trace fetches from one block read it from the store a bounded number of times`() {
        val traceId = seeded.traceIds.getValue(HOME)
        val readsPerFetch =
            List(REPEATED_FETCHES) {
                val before = blockReads()
                assertThat(traceFound(HOME, traceId)).describedAs("fetch ${it + 1} of trace $traceId").isTrue()
                // The gate logs a request when it ends; give the log a moment to show the last one.
                Thread.sleep(GATE_LOG_SETTLE.toMillis())
                blockReads() - before
            }

        assertThat(readsPerFetch.drop(CACHE_FILL_FETCHES))
            .describedAs("block reads per fetch, in order: $readsPerFetch")
            .containsOnly(0)
    }

    /**
     * Grafana's data source proxy allow-list does not cover a backend plugin's queries, so Grafana
     * answers only the URL `local create` prints: a request that reaches its published port under any
     * other Host (a page in the browser that rebound its own name to 127.0.0.1) is redirected there,
     * a query included, while the printed URL serves Grafana's page.
     */
    @Test
    @Order(9)
    fun `Grafana serves the URL local create prints and redirects a request under any other host to it`() {
        val printed = grafanaUrl(manifest.grafanaPort)
        assertThat(printed).isEqualTo(grafanaBase())
        val page = ObservabilityBackends.get("$printed/", HOME)
        assertThat(page.statusCode()).describedAs(page.body().take(LOG_TAIL)).isEqualTo(HTTP_OK)

        for ((method, path) in listOf("GET" to "/api/datasources", "POST" to "/api/ds/query")) {
            val answer = rawRequest(method, manifest.grafanaPort, "rebound.example", path)
            assertThat(answer.lines().first()).describedAs(answer).contains(" 301 ")
            assertThat(answer.lines()).describedAs(answer).anyMatch { it.equals("Location: $printed$path", ignoreCase = true) }
        }
    }

    /**
     * A bodiless request with its own Host header, over a plain socket: Java's HTTP client will not
     * send one. Returns the status line and the headers.
     */
    private fun rawRequest(
        method: String,
        port: Int,
        host: String,
        path: String,
    ): String =
        Socket(Constants.LocalStack.LOOPBACK, port).use { socket ->
            socket.soTimeout = Duration.ofSeconds(PROBE_TIMEOUT_SECONDS.toLong()).toMillis().toInt()
            val request = "$method $path HTTP/1.1\r\nHost: $host\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray())
            socket
                .getInputStream()
                .bufferedReader()
                .lineSequence()
                .takeWhile { it.isNotEmpty() }
                .joinToString("\n")
        }

    private fun grafanaBase(): String = "http://${Constants.LocalStack.LOOPBACK}:${manifest.grafanaPort}"

    private fun dashboardStatus(uid: String): Int = ObservabilityBackends.get("${grafanaBase()}/api/dashboards/uid/$uid", HOME).statusCode()

    private fun homeDashboardUid(): String =
        Json
            .parseToJsonElement(File(stack, "dashboards/current/${Constants.Grafana.HOME_DASHBOARD_PATH}").readText())
            .jsonObject
            .getValue("uid")
            .jsonPrimitive.content

    /**
     * The backends and Grafana run as root, so on Linux the state they wrote under the stack directory
     * is root's; a container removes it so the temporary directory can be deleted.
     */
    private fun removeRootOwnedState() {
        GenericContainer(Constants.LocalStack.HEALTHCHECK_TOOLS_IMAGE)
            .withCreateContainerCmdModifier { it.hostConfig?.withBinds(Bind(stack.absolutePath, Volume("/stack"))) }
            .withCommand("rm", "-rf", "/stack/${Constants.LocalStack.CACHE_DIR}", "/stack/${Constants.LocalStack.GRAFANA_DATA_DIR}")
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

private const val LOG_TAIL = 4000
