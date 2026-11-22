package com.rustyrazorblade.easydblab.services

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.K3sDiagnostics.clusterDiagnostics
import com.rustyrazorblade.easydblab.K3sDiagnostics.withClusterDiagnostics
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.BackendEndpoints
import com.rustyrazorblade.easydblab.configuration.BackendStorage
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.Placement
import com.rustyrazorblade.easydblab.configuration.ProfileRegion
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContractSource
import com.rustyrazorblade.easydblab.configuration.grafana.BackendUrls
import com.rustyrazorblade.easydblab.configuration.grafana.DocumentsBucket
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardConfigMaps
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDatasourceSet
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.ServiceAccountCa
import com.rustyrazorblade.easydblab.configuration.pyroscope.CpuSampleJfr
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.proxy.ProxiedHttpClientFactory
import com.rustyrazorblade.easydblab.services.access.ClusterServices
import com.rustyrazorblade.easydblab.services.access.ServiceAccess
import com.rustyrazorblade.easydblab.services.access.ServiceEndpoint
import com.rustyrazorblade.easydblab.ssh.Response
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The observability backends and Grafana run on a cluster that has no node labelled as the control
 * plane, as an environment easy-db-lab did not build would have: with an empty [Placement] and their
 * state on claims, every one reaches Ready in the pod network, Grafana's datasources name each
 * backend by its Service, and a backend whose pod is replaced is reached again by that name.
 *
 * The cluster is a K3s container of its own, because the test removes the control-plane label K3s
 * gives its server, and the workloads run in `default`, the namespace their Service DNS names and
 * cross-backend URLs (Tempo's remote write to Mimir) carry. The one edit to a rendered
 * configuration is the harness's: the S3 endpoint and credentials point at the shared LocalStack,
 * so the backends start against a real object store.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PortableObservabilityPlacementIntegrationTest {
    companion object {
        private const val NAMESPACE = "default"
        private const val CONTROL_PLANE_LABEL = "node-role.kubernetes.io/control-plane"
        private const val TENANT = "default"
        private const val COLLECTOR_CONFIG_MAP = "otel-collector-config"

        /** K3s's bundled provisioner, standing in for #1035's retained StorageClass. */
        private const val LOCAL_PATH_CLASS = "local-path"
        private const val READY_TIMEOUT_SECONDS = 420L

        /** A two-folder tree standing in for the shipped one: on a claim it reaches Grafana as ConfigMaps. */
        private val DASHBOARD_TREE =
            mapOf(
                "portable-a/one.json" to """{"uid":"portable-one","title":"Portable One","panels":[],"schemaVersion":39}""",
                "portable-b/two.json" to """{"uid":"portable-two","title":"Portable Two","panels":[],"schemaVersion":39}""",
            )
        private const val EXEC_TIMEOUT_SECONDS = 60L

        /** The images local-path provisioning runs, so a claim never waits on a registry. */
        private const val LOCAL_PATH_PROVISIONER_IMAGE = "rancher/local-path-provisioner:v0.0.34"
        private const val LOCAL_PATH_HELPER_IMAGE = "rancher/mirrored-library-busybox:1.37.0"

        private val WORKLOADS = listOf("mimir", "loki", "tempo", "pyroscope", "grafana")

        /** The fixed NodePorts the AWS hosting publishes, with the readiness path each answers on. */
        private val PUBLISHED =
            mapOf(
                Constants.K8s.MIMIR_NODE_PORT to "/ready",
                Constants.K8s.LOKI_NODE_PORT to "/ready",
                Constants.K8s.TEMPO_NODE_PORT to "/ready",
                Constants.K8s.PYROSCOPE_NODE_PORT to "/ready",
                Constants.K8s.GRAFANA_NODE_PORT to "/api/health",
            )

        /** A process on a node's own network, as the db-node reconciler and a stress pod run. */
        private const val CURL_IMAGE = "curlimages/curl:8.11.1"
        private const val PROFILED_SERVICE = "portable-hostnet"
        private const val PROFILE_ARRIVAL_SECONDS = 180L
        private const val POLL_MILLIS = 2_000L

        /** The redirected sender: its cluster label and one sample of each signal it ships. */
        private const val SENDER_CLUSTER = "redirected-sender"
        private const val REDIRECT_METRIC = "edl_redirect_probe"
        private const val REDIRECT_LOG_LINE = "redirected log line"
        private const val REDIRECT_PROFILED_SERVICE = "portable-redirected"
        private const val SA_DIR = "/var/run/secrets/kubernetes.io/serviceaccount"

        @Container
        @JvmStatic
        val k3s: K3sContainer =
            K3sContainer(DockerImageName.parse(SharedK3s.K3S_IMAGE))
                .withPrivilegedMode(true)
                .withCreateContainerCmdModifier { cmd ->
                    cmd.hostConfig!!
                        .withCgroupnsMode("host")
                        .withUlimits(listOf(Ulimit("nofile", 65536L, 65536L)))
                }.withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("k3s")))
                .withEnv("K3S_SNAPSHOTTER", "native")
                .let {
                    it.addExposedPorts(*PUBLISHED.keys.toIntArray())
                    (it as K3sContainer).withPreloadedImages(
                        MimirManifestBuilder.IMAGE,
                        Constants.K8s.MIMIR_MEMCACHED_IMAGE,
                        LokiManifestBuilder.IMAGE,
                        TempoManifestBuilder.IMAGE,
                        PyroscopeManifestBuilder.SERVER_IMAGE,
                        Constants.PyroscopeServer.FRONT_IMAGE,
                        Constants.Grafana.Documents.SIGV4_PROXY_IMAGE,
                        Constants.Grafana.Documents.WEB_SERVER_IMAGE,
                        LOCAL_PATH_PROVISIONER_IMAGE,
                        LOCAL_PATH_HELPER_IMAGE,
                        CURL_IMAGE,
                        OtelManifestBuilder.IMAGE,
                    )
                }
    }

    private lateinit var client: KubernetesClient
    private lateinit var templates: TemplateService
    private lateinit var stateManager: ClusterStateManager

    private val bucket = "portable-placement-${UUID.randomUUID().toString().take(8)}"
    private val state =
        ClusterState(
            name = "portable",
            s3Bucket = bucket,
            // A cluster the operator brought: no control node in its state.
            initConfig = InitConfig(region = SharedLocalStack.region(), tenant = TENANT),
        )

    private val hosting =
        ObservabilityHosting.AWS.copy(
            placement = Placement.ANY_NODE,
            storage = BackendStorage.RetainedClaim(LOCAL_PATH_CLASS, Quantity("1Gi")),
        )

    @BeforeAll
    fun setup() {
        SharedLocalStack.s3Client().use { SharedLocalStack.createBucketIfMissing(it, bucket) }
        client = KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        SharedK3s.ensurePodAdmission(client, NAMESPACE)
        val node =
            client
                .nodes()
                .list()
                .items
                .single()
        client.nodes().withName(node.metadata.name).edit { it.apply { metadata.labels.remove(CONTROL_PLANE_LABEL) } }

        stateManager = mock<ClusterStateManager>()
        whenever(stateManager.load()).thenReturn(state)
        templates = TemplateService(stateManager)

        resources().forEach { client.resource(it).serverSideApply() }
    }

    @AfterAll
    fun tearDown() {
        client.close()
    }

    /** Every resource the stack deploy applies for these workloads, the backends pointed at LocalStack. */
    private fun resources(): List<HasMetadata> {
        val clusterConfig =
            ConfigMapBuilder()
                .withNewMetadata()
                .withName(ClusterConfigData.NAME)
                .withNamespace(NAMESPACE)
                .endMetadata()
                .withData<String, String>(ClusterConfigData.of(state, SharedLocalStack.region()))
                .build()
        val datasources =
            ConfigMapBuilder()
                .withNewMetadata()
                .withName("grafana-datasources")
                .withNamespace(NAMESPACE)
                .endMetadata()
                .addToData("datasources.yaml", GrafanaDatasourceSet.build(TenantSet.of(TENANT), BackendUrls.IN_CLUSTER).toYaml())
                .build()
        return listOf(clusterConfig, datasources) +
            (
                MimirManifestBuilder(templates).buildAllResources(hosting) +
                    LokiManifestBuilder(templates).buildAllResources(hosting) +
                    TempoManifestBuilder(templates).buildAllResources(hosting) +
                    PyroscopeManifestBuilder(templates).buildServerResources(hosting) +
                    GrafanaDashboardConfigMaps.mountInto(
                        GrafanaManifestBuilder(templates).buildAllResources(DocumentsBucket(bucket, SharedLocalStack.region()), hosting),
                        DASHBOARD_TREE,
                    )
            ).map(::toLocalStack)
    }

    /**
     * [resource], with a backend configuration's S3 endpoint and credentials pointed at LocalStack.
     * Only the backend's own config file is edited: the Pyroscope ConfigMap also carries the ingest
     * front's HAProxy config, which names no S3 endpoint and runs as rendered.
     */
    private fun toLocalStack(resource: HasMetadata): HasMetadata {
        if (resource !is ConfigMap) return resource
        val edit: Pair<String, (String) -> String>? =
            when (resource.metadata.name) {
                "mimir-config" -> MimirManifestBuilder.CONFIG_FILE to ObservabilityBackends::mimirConfig
                "loki-config" -> LokiManifestBuilder.CONFIG_FILE to ObservabilityBackends::lokiConfig
                "tempo-config" -> TempoManifestBuilder.CONFIG_FILE to ObservabilityBackends::tempoConfig
                "pyroscope-config" -> PyroscopeManifestBuilder.SERVER_CONFIG_KEY to ::pyroscopeConfig
                else -> null
            }
        return when (edit) {
            null -> resource
            else -> {
                val (key, rewrite) = edit
                val content = checkNotNull(resource.data[key]) { "${resource.metadata.name} has no $key" }
                ConfigMapBuilder(resource).addToData(key, inCluster(rewrite(content))).build()
            }
        }
    }

    /** The harness edits name LocalStack as the Docker host sees it; a pod reaches it on the Docker network. */
    private fun inCluster(config: String): String =
        config.replace("host.testcontainers.internal:${SharedLocalStack.hostPort()}", SharedLocalStack.networkAddress())

    /** Pyroscope's rendered config, pointed at LocalStack. */
    private fun pyroscopeConfig(rendered: String): String {
        val endpointLine = "    endpoint: s3.${SharedLocalStack.region()}.amazonaws.com"
        check(rendered.contains(endpointLine)) { "rendered pyroscope config no longer names the regional S3 endpoint" }
        return rendered.replace(
            endpointLine,
            """
            |    endpoint: ${SharedLocalStack.networkAddress()}
            |    insecure: true
            |    force_path_style: true
            |    access_key_id: ${SharedLocalStack.accessKey()}
            |    secret_access_key: ${SharedLocalStack.secretKey()}
            """.trimMargin(),
        )
    }

    private fun isReady(pod: Pod?): Boolean =
        pod?.metadata?.deletionTimestamp == null &&
            pod
                ?.status
                ?.conditions
                .orEmpty()
                .any { it.type == "Ready" && it.status == "True" }

    /** The Ready pod of [app] whose uid is not [replacing], waiting for it to exist. */
    private fun ready(
        app: String,
        replacing: String? = null,
    ): Pod =
        withClusterDiagnostics(client, NAMESPACE) {
            val selector = client.pods().inNamespace(NAMESPACE).withLabel("app.kubernetes.io/name", app)
            selector
                .informOnCondition { pods -> pods.any { isReady(it) && it.metadata.uid != replacing } }
                .get(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            selector.list().items.single { isReady(it) && it.metadata.uid != replacing }
        }

    /** Runs [command] in the Grafana container and returns its output. */
    private fun inGrafana(vararg command: String): String {
        val pod = ready("grafana")
        return client
            .pods()
            .inNamespace(NAMESPACE)
            .withName(pod.metadata.name)
            .inContainer("grafana")
            .redirectingOutput()
            .exec(*command)
            .use { watch ->
                val output = watch.output.readAllBytes().toString(Charsets.UTF_8)
                watch.exitCode().get(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                output
            }
    }

    @Test
    @Order(1)
    fun `every backend and Grafana reaches Ready on a node with no control-plane label, in the pod network`() {
        assertThat(
            client
                .nodes()
                .list()
                .items
                .single()
                .metadata.labels,
        ).doesNotContainKey(CONTROL_PLANE_LABEL)

        WORKLOADS.forEach { app ->
            val pod = ready(app)
            assertThat(pod.spec.hostNetwork ?: false).describedAs(app).isFalse()
            assertThat(
                pod.spec.containers
                    .flatMap { it.ports.orEmpty() }
                    .map { it.hostPort },
            ).describedAs("$app host ports").containsOnlyNulls()
            assertThat(pod.spec.nodeSelector.orEmpty()).describedAs(app).isEmpty()
            assertThat(pod.spec.volumes.mapNotNull { it.persistentVolumeClaim?.claimName }).describedAs(app).isNotEmpty()
            assertThat(pod.status.containerStatuses).describedAs(app).allSatisfy {
                assertThat(it.restartCount).describedAs("${it.name} restarts").isZero()
            }
        }
    }

    @Test
    @Order(2)
    fun `Grafana reaches every backend by the Service DNS name its datasources use`() {
        val urls = GrafanaDatasourceSet.build(TenantSet.of(TENANT), BackendUrls.IN_CLUSTER).datasources.mapNotNull { it.url }
        assertThat(urls).allSatisfy { assertThat(it).doesNotContain("localhost") }
        WORKLOADS.forEach { ready(it) }

        val backends =
            mapOf(
                "${BackendUrls.IN_CLUSTER.metrics.removeSuffix("/prometheus")}/ready" to "mimir",
                "${BackendUrls.IN_CLUSTER.logs}/ready" to "loki",
                "${BackendUrls.IN_CLUSTER.traces}/ready" to "tempo",
                "${BackendUrls.IN_CLUSTER.profiles}/ready" to "pyroscope",
            )
        backends.forEach { (url, app) ->
            val status = inGrafana("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", url)
            assertThat(status).describedAs("$app at $url").isEqualTo("200")
        }
    }

    @Test
    @Order(3)
    fun `a backend whose pod is replaced is reached again through its Service`() {
        val before = ready("loki")
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withName(before.metadata.name)
            .delete()

        val after = ready("loki", replacing = before.metadata.uid)
        assertThat(after.metadata.uid).isNotEqualTo(before.metadata.uid)
        assertThat(after.status.podIP).isNotNull()

        val url = "${BackendUrls.IN_CLUSTER.logs}/ready"
        val status = inGrafana("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", url)
        assertThat(status).describedAs("loki at $url after its pod was replaced").isEqualTo("200")
    }

    /**
     * With its state on a claim Grafana has no node directory to receive the dashboard tree: the
     * mounted ConfigMaps put each file in a folder named for its directory, as the host-path copy does.
     * The search goes through the grafana Service by its DNS name, so it also proves the Service
     * reaches Grafana through the front on [Constants.K8s.GRAFANA_PORT].
     */
    @Test
    @Order(5)
    fun `on a claim Grafana loads the dashboards it mounts from ConfigMaps into their folders`() {
        val grafana = ClusterServices.GRAFANA
        val searchUrl = "http://${BackendEndpoints.host(grafana.service)}:${grafana.servicePort}/api/search?type=dash-db"
        val search = inGrafana("curl", "-s", "-u", "admin:admin", searchUrl)

        assertThat(search).contains("\"uid\":\"portable-one\"", "\"uid\":\"portable-two\"")
        assertThat(search).contains("\"folderTitle\":\"portable-a\"", "\"folderTitle\":\"portable-b\"")
    }

    /**
     * The kit-driven collector sync, run for real against this cluster. Its state has no control
     * node, which off owned nodes is not a reason to fail: the client comes from the kubeconfig,
     * the collector it applies reaches every
     * backend by Service DNS.
     */
    @Test
    @Order(4)
    fun `the collector sync runs with no control node and applies a collector with no node IP`() {
        assertThat(state.getControlHost()).isNull()
        val clientProvider = mock<K8sClientProvider>()
        whenever(clientProvider.createClient()).thenAnswer {
            KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        }
        val k8sService = DefaultK8sService(clientProvider, EventBus())
        DefaultOtelSyncService(
            clientProvider,
            k8sService,
            OtelManifestBuilder(templates),
            stateManager,
            ConfigChangeReport(k8sService, EventBus()),
            // The local Docker capabilities with this test's hosting, which no recorded environment
            // pairs yet: the claim storage arrives with #1035.
            ClusterContractSource {
                ClusterContract.of(state).copy(nodeCapabilities = NodeCapabilities.LOCAL_DOCKER, hosting = hosting)
            },
            ProfileRegion.NONE,
        ).syncConfigMap().getOrThrow()

        val config =
            client
                .configMaps()
                .inNamespace(NAMESPACE)
                .withName(COLLECTOR_CONFIG_MAP)
                .get()
                .data
                .values
                .single()
        assertThat(
            config.replace("0.0.0.0:", ""),
        ).describedAs("no endpoint at a node IP").doesNotContainPattern("""\b\d{1,3}(\.\d{1,3}){3}:\d+""")
        assertThat(config).contains(BackendEndpoints.mimirPush(), BackendEndpoints.lokiOtlp(), BackendEndpoints.tempoOtlpGrpc())
        assertThat(
            client
                .apps()
                .daemonSets()
                .inNamespace(NAMESPACE)
                .withName(Constants.OtelCollector.SERVICE_NAME)
                .get(),
        ).isNotNull()
    }

    /**
     * On nodes easy-db-lab owns, EMR, a browser and the operator's scripts reach each backend and
     * Grafana on its fixed NodePort at a node's address. The K3s container's own address stands in
     * for that node: the port the test dials is the one Docker maps from the node's NodePort.
     */
    @Test
    @Order(6)
    fun `each published NodePort reaches its backend from the node address`() {
        WORKLOADS.forEach { ready(it) }
        val services =
            client
                .services()
                .inNamespace(NAMESPACE)
                .list()
                .items
        // Read back from the API server, which assigns a random node port to any port of a NodePort
        // Service that has none: exactly the fixed ports means no internal port was published.
        val published = services.filter { it.spec.type == "NodePort" }.flatMap { it.spec.ports }.map { it.nodePort }
        assertThat(published).containsExactlyInAnyOrderElementsOf(PUBLISHED.keys + Constants.K8s.TEMPO_OTLP_GRPC_NODE_PORT)

        PUBLISHED.forEach { (nodePort, path) ->
            val url = "http://${k3s.host}:${k3s.getMappedPort(nodePort)}$path"
            val status = nodeHttp.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()
            assertThat(status).describedAs("NodePort $nodePort at $url").isEqualTo(200)
        }
    }

    /**
     * A process on a node's own network, as the db-node reconciler and a stress pod are, resolves the
     * Pyroscope Service through cluster DNS (`ClusterFirstWithHostNet`) and posts a JFR chunk to it;
     * the profile is then read back under the tenant.
     */
    @Test
    @Order(7)
    fun `a host-network pod posts a JFR to the Pyroscope Service and the profile is read back under the tenant`() {
        ready("pyroscope")
        client.resource(jfrConfigMap(CpuSampleJfr.bytes())).serverSideApply()
        val job = client.resource(hostNetworkSender()).create()

        val done =
            client
                .batch()
                .v1()
                .jobs()
                .inNamespace(NAMESPACE)
                .withName(job.metadata.name)
                .waitUntilCondition(
                    { (it?.status?.succeeded ?: 0) > 0 || (it?.status?.failed ?: 0) > 0 },
                    READY_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
        assertThat(done.status.succeeded).describedAs("JFR sender job").isEqualTo(1)

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROFILE_ARRIVAL_SECONDS)
        var series = cpuSeries()
        while (!series.contains(PROFILED_SERVICE) && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
            series = cpuSeries()
        }
        assertThat(series).describedAs("Pyroscope series under tenant $TENANT").contains(PROFILED_SERVICE)
    }

    /**
     * A second cluster redirected to this one (`--redirect-telemetry <node address>`, no overrides)
     * lands all four signals in this stack, whose backends declare no host port. The sender's OTel
     * collector runs with its rendered redirect configuration, unedited, on the Docker network the K3s
     * node is on, so it dials the derived endpoints themselves: Mimir remote write, Loki OTLP and Tempo
     * OTLP gRPC on their NodePorts at the node address. Profiles go to the derived Pyroscope endpoint
     * as the redirected eBPF agent and sidecar send them. Each signal is read back under the tenant,
     * carrying the sender's cluster label.
     */
    @Test
    @Order(8)
    fun `a cluster redirected to this one lands metrics, logs, traces and profiles in its backends`() {
        WORKLOADS.forEach { ready(it) }
        val redirect = TelemetryRedirect.fromBaseHost(bridgeAddress(k3s.containerId))
        val collector = startRedirectedCollector(redirect)
        try {
            val nanos = System.currentTimeMillis() * 1_000_000
            val traceId = UUID.randomUUID().toString().replace("-", "")
            postToCollector(
                collector,
                "/v1/metrics",
                """
                {"resourceMetrics":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"redirected"}}]},
                 "scopeMetrics":[{"metrics":[{"name":"$REDIRECT_METRIC","gauge":{"dataPoints":[{"asDouble":3,"timeUnixNano":"$nanos"}]}}]}]}]}
                """.trimIndent(),
            )
            postToCollector(
                collector,
                "/v1/logs",
                """
                {"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"redirected"}}]},
                 "scopeLogs":[{"logRecords":[{"timeUnixNano":"$nanos","body":{"stringValue":"$REDIRECT_LOG_LINE"}}]}]}]}
                """.trimIndent(),
            )
            postToCollector(
                collector,
                "/v1/traces",
                """
                {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"redirected"}}]},
                 "scopeSpans":[{"spans":[{"traceId":"$traceId","spanId":"${traceId.take(16)}","name":"probe","kind":1,
                 "startTimeUnixNano":"$nanos","endTimeUnixNano":"${nanos + 1_000_000}"}]}]}]}
                """.trimIndent(),
            )
            sendProfileTo(redirect.profiles)

            val metric =
                awaitBody("metric $REDIRECT_METRIC") {
                    nodeGet(
                        Constants.K8s.MIMIR_NODE_PORT,
                        "/prometheus/api/v1/query?query=$REDIRECT_METRIC",
                    ).takeIf { it.contains("\"value\"") }
                }
            assertThat(metric).contains("\"cluster\":\"$SENDER_CLUSTER\"")

            val logQuery = URLEncoder.encode("{cluster=\"$SENDER_CLUSTER\"}", Charsets.UTF_8)
            val logs =
                awaitBody("log line") {
                    nodeGet(Constants.K8s.LOKI_NODE_PORT, "/loki/api/v1/query_range?query=$logQuery&since=10m")
                        .takeIf { it.contains(REDIRECT_LOG_LINE) }
                }
            assertThat(logs).contains(REDIRECT_LOG_LINE)

            awaitBody("trace $traceId") {
                nodeResponse(Constants.K8s.TEMPO_NODE_PORT, "/api/v2/traces/$traceId").takeIf { it.statusCode() == 200 }?.body()
            }
            awaitBody(
                "profile $REDIRECT_PROFILED_SERVICE",
            ) { cpuSeries(REDIRECT_PROFILED_SERVICE).takeIf { it.contains(REDIRECT_PROFILED_SERVICE) } }
        } finally {
            collector.stop()
        }
    }

    /**
     * `down`'s tail flush, run for real against backends that declare no host port: the Loki and
     * Mimir ingester shutdowns and the Tempo drain reach their backends through [ObservabilityHttp]
     * over the service-access seam, which dials each Service's NodePort at the node, and the
     * collector stop and the scale-downs go through the Kubernetes API. The seam here returns the
     * port Docker maps from the node's NodePort, where on AWS it returns the node address and the
     * NodePort through the tunnel. Tempo's local blocks are on its claim, so the node command that
     * lists them reads the claim's directory on the K3s node.
     *
     * Runs last: it stops the collector and scales Loki and Mimir to 0.
     */
    @Test
    @Order(9)
    fun `the tail flush reaches Loki, Mimir and Tempo on their NodePorts and completes`() {
        WORKLOADS.forEach { ready(it) }
        val clientProvider = mock<K8sClientProvider>()
        whenever(clientProvider.createClient()).thenAnswer {
            KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        }
        val atNodePort = ServiceAccess { target -> ServiceEndpoint.Plain(k3s.host, k3s.getMappedPort(target.nodePort)) }
        val http = DefaultObservabilityHttp(atNodePort, { TENANT }, ProxiedHttpClientFactory())
        val workloads = K8sBackendWorkloads(clientProvider, NAMESPACE, pollInterval = Duration.ofSeconds(1))
        val timeouts =
            FlushTimeouts(
                shutdown = Duration.ofMinutes(2),
                scaleDown = Duration.ofMinutes(2),
                sendersStop = Duration.ofMinutes(2),
                tempoDrain = Duration.ofMinutes(5),
            )
        val service =
            DefaultTeardownFlushService
                .builder()
                .lokiFlush(LokiTailFlush(http, workloads, timeouts))
                .mimirFlush(MimirTailFlush(http, workloads, timeouts))
                .tempoDrain(TempoTailFlush(http, tempoNode(), timeouts))
                .telemetrySenders(K8sTelemetrySenders(clientProvider, NAMESPACE, pollInterval = Duration.ofSeconds(1)))
                .annotationMirror(RecordingAnnotationMirror())
                .annotationBackupService(mock<GrafanaAnnotationBackupService>())
                .eventBus(EventBus())
                .timeouts(timeouts)
                .build()
        val node = ClusterHost(publicIp = "", privateIp = bridgeAddress(k3s.containerId), alias = "control0", availabilityZone = "")

        val outcome = service.saveTail(node, state, setOf(TailSignal.LOGS, TailSignal.METRICS, TailSignal.TRACES))

        assertThat(outcome.failed).describedAs { "failed steps\n${clusterDiagnostics(client, NAMESPACE)}" }.isEmpty()
        assertThat(outcome.saved.keys).containsExactlyInAnyOrder(TailSignal.LOGS, TailSignal.METRICS, TailSignal.TRACES)
        assertThat(outcome.backends)
            .containsEntry(Constants.K8s.LOKI_APP_LABEL, BackendState.SCALED_TO_ZERO)
            .containsEntry(Constants.K8s.MIMIR_APP_LABEL, BackendState.SCALED_TO_ZERO)
    }

    /**
     * The node commands the Tempo drain runs, run on the K3s node as root. Tempo's `/var/tempo` is
     * its claim here, not [TempoManifestBuilder.DATA_HOST_PATH], so the WAL directory the command
     * names is read from the claim's directory on the node.
     */
    private fun tempoNode(): RemoteOperationsService {
        val walOnNode = "${claimDirectory("tempo-data")}/live-store/wal"
        val node = mock<RemoteOperationsService>()
        whenever(node.executeRemotely(any(), any(), any(), any())).thenAnswer { invocation ->
            val command = invocation.getArgument<String>(1).replace(TempoTailFlush.WAL_DIR, walOnNode)
            val result = k3s.execInContainer("sh", "-c", "sudo() { \"\$@\"; }\n$command")
            check(result.exitCode == 0) { "node command failed (${result.exitCode}): ${result.stderr}" }
            Response(result.stdout, result.stderr)
        }
        return node
    }

    /** The directory on the node that holds the volume bound to [claim]. */
    private fun claimDirectory(claim: String): String {
        val volume =
            client
                .persistentVolumeClaims()
                .inNamespace(NAMESPACE)
                .withName(claim)
                .get()
                .spec.volumeName
        val spec =
            client
                .persistentVolumes()
                .withName(volume)
                .get()
                .spec
        return checkNotNull(spec.hostPath?.path ?: spec.local?.path) { "volume $volume of $claim has no node directory" }
    }

    /** [containerId]'s address on Docker's default bridge: the node address another host dials. */
    private fun bridgeAddress(containerId: String): String {
        val networks =
            DockerClientFactory
                .instance()
                .client()
                .inspectContainerCmd(containerId)
                .exec()
                .networkSettings.networks
        return checkNotNull(networks["bridge"]?.ipAddress) { "no address on the default bridge: $networks" }
    }

    /** The redirected sender's OTel collector, its rendered configuration unedited. */
    private fun startRedirectedCollector(redirect: TelemetryRedirect): GenericContainer<*> {
        val config =
            OtelManifestBuilder(templates)
                .buildConfigMap(NodeCapabilities.AWS, telemetryRedirect = redirect)
                .data
                .values
                .single()
        return GenericContainer(DockerImageName.parse("otel/opentelemetry-collector-contrib:${Constants.OtelCollector.VERSION}"))
            .withCopyToContainer(Transferable.of(config), "/etc/otel-collector-config.yaml")
            .withCopyToContainer(Transferable.of("placeholder"), "$SA_DIR/token")
            .withCopyToContainer(Transferable.of(NAMESPACE), "$SA_DIR/namespace")
            // kubelet_stats loads the in-cluster CA when it starts.
            .withCopyToContainer(Transferable.of(ServiceAccountCa.pem()), "$SA_DIR/ca.crt")
            .withEnv("K8S_NODE_NAME", "sender0")
            .withEnv("K8S_NODE_IP", "127.0.0.1")
            .withEnv("CLUSTER_NAME", SENDER_CLUSTER)
            .withEnv("TENANT", TENANT)
            .withEnv("KUBERNETES_SERVICE_HOST", "127.0.0.1")
            .withEnv("KUBERNETES_SERVICE_PORT", "1")
            .withCommand("--config=/etc/otel-collector-config.yaml")
            .withExposedPorts(Constants.K8s.OTEL_HTTP_PORT, Constants.K8s.OTEL_HEALTH_PORT)
            .waitingFor(Wait.forHttp("/").forPort(Constants.K8s.OTEL_HEALTH_PORT).withStartupTimeout(Duration.ofMinutes(2)))
            .apply { start() }
    }

    private fun postToCollector(
        collector: GenericContainer<*>,
        path: String,
        body: String,
    ) {
        val request =
            HttpRequest
                .newBuilder(URI("http://${collector.host}:${collector.getMappedPort(Constants.K8s.OTEL_HTTP_PORT)}$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = nodeHttp.send(request, HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).describedAs("$path: ${response.body()}").isEqualTo(200)
    }

    /** Posts a folded profile to [profilesUrl] from a host on the node's network, as a redirected agent sends. */
    private fun sendProfileTo(profilesUrl: String) {
        val name = URLEncoder.encode("$REDIRECT_PROFILED_SERVICE{cluster=$SENDER_CLUSTER}", Charsets.UTF_8)
        GenericContainer(DockerImageName.parse(CURL_IMAGE))
            .withCommand(
                "--fail",
                "--silent",
                "--show-error",
                "--header",
                "${Constants.Observability.TENANT_HEADER}: $TENANT",
                "--data-binary",
                "main;work;compute 100",
                "$profilesUrl/ingest?name=$name&format=folded",
            ).withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(EXEC_TIMEOUT_SECONDS)))
            .use { it.start() }
    }

    private fun nodeResponse(
        nodePort: Int,
        pathAndQuery: String,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("http://${k3s.host}:${k3s.getMappedPort(nodePort)}$pathAndQuery"))
                .header(Constants.Observability.TENANT_HEADER, TENANT)
                .GET()
                .build()
        return nodeHttp.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun nodeGet(
        nodePort: Int,
        pathAndQuery: String,
    ): String = nodeResponse(nodePort, pathAndQuery).body()

    /** Polls [probe] until it returns a body, failing with [what] once the arrival window passes. */
    private fun awaitBody(
        what: String,
        probe: () -> String?,
    ): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROFILE_ARRIVAL_SECONDS)
        var body = probe()
        while (body == null && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
            body = probe()
        }
        return checkNotNull(body) { "$what did not arrive under tenant $TENANT within ${PROFILE_ARRIVAL_SECONDS}s" }
    }

    private val nodeHttp: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(EXEC_TIMEOUT_SECONDS)).build()

    private fun jfrConfigMap(jfr: ByteArray): ConfigMap =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName("portable-jfr")
            .withNamespace(NAMESPACE)
            .endMetadata()
            .addToBinaryData("chunk.jfr", Base64.getEncoder().encodeToString(jfr))
            .build()

    /** A Job on the node's network that posts the chunk to the Pyroscope Service, as the reconciler posts. */
    private fun hostNetworkSender(): Job {
        val until = Instant.now().epochSecond
        val name = URLEncoder.encode("$PROFILED_SERVICE{cluster=${state.name}}", Charsets.UTF_8)
        val url = "${BackendEndpoints.pyroscope()}/ingest?name=$name&format=jfr&from=${until - 60}&until=$until"
        return JobBuilder()
            .withNewMetadata()
            .withName("portable-jfr-sender-${System.currentTimeMillis() / 1000}")
            .withNamespace(NAMESPACE)
            .endMetadata()
            .withNewSpec()
            .withBackoffLimit(0)
            .withNewTemplate()
            .withNewSpec()
            .withHostNetwork(true)
            .withDnsPolicy("ClusterFirstWithHostNet")
            .withRestartPolicy("Never")
            .addNewContainer()
            .withName("sender")
            .withImage(CURL_IMAGE)
            .withImagePullPolicy("IfNotPresent")
            .withArgs(
                "--fail",
                "--silent",
                "--show-error",
                "--request",
                "POST",
                "--header",
                "Content-Type: application/octet-stream",
                "--header",
                "${Constants.Observability.TENANT_HEADER}: $TENANT",
                "--data-binary",
                "@/jfr/chunk.jfr",
                url,
            ).addNewVolumeMount()
            .withName("jfr")
            .withMountPath("/jfr")
            .endVolumeMount()
            .endContainer()
            .addNewVolume()
            .withName("jfr")
            .withNewConfigMap()
            .withName("portable-jfr")
            .endConfigMap()
            .endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
    }

    /** The CPU series Pyroscope holds for [service] under the tenant, read on its NodePort. */
    private fun cpuSeries(service: String = PROFILED_SERVICE): String {
        val base = "http://${k3s.host}:${k3s.getMappedPort(Constants.K8s.PYROSCOPE_NODE_PORT)}"
        val body =
            """{"matchers":["{service_name=\"$service\",__profile_type__=\"process_cpu:cpu:nanoseconds:cpu:nanoseconds\"}"],""" +
                """"start":${Instant.now().minusSeconds(3600).toEpochMilli()},"end":${Instant.now().plusSeconds(60).toEpochMilli()}}"""
        val request =
            HttpRequest
                .newBuilder(URI("$base/querier.v1.QuerierService/Series"))
                .header(Constants.Observability.TENANT_HEADER, TENANT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return nodeHttp.send(request, HttpResponse.BodyHandlers.ofString()).body()
    }
}
