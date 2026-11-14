package com.rustyrazorblade.easydblab.services

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.K3sDiagnostics.withClusterDiagnostics
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.BackendStorage
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.Placement
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.grafana.BackendUrls
import com.rustyrazorblade.easydblab.configuration.grafana.DocumentsBucket
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDatasourceSet
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.Quantity
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
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

        /** K3s's bundled provisioner, standing in for #1035's retained StorageClass. */
        private const val LOCAL_PATH_CLASS = "local-path"
        private const val READY_TIMEOUT_SECONDS = 420L
        private const val EXEC_TIMEOUT_SECONDS = 60L

        /** The images local-path provisioning runs, so a claim never waits on a registry. */
        private const val LOCAL_PATH_PROVISIONER_IMAGE = "rancher/local-path-provisioner:v0.0.34"
        private const val LOCAL_PATH_HELPER_IMAGE = "rancher/mirrored-library-busybox:1.37.0"

        private val WORKLOADS = listOf("mimir", "loki", "tempo", "pyroscope", "grafana")

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
                    (it as K3sContainer).withPreloadedImages(
                        MimirManifestBuilder.IMAGE,
                        Constants.K8s.MIMIR_MEMCACHED_IMAGE,
                        LokiManifestBuilder.IMAGE,
                        TempoManifestBuilder.IMAGE,
                        PyroscopeManifestBuilder.SERVER_IMAGE,
                        Constants.Grafana.Documents.SIGV4_PROXY_IMAGE,
                        Constants.Grafana.Documents.WEB_SERVER_IMAGE,
                        LOCAL_PATH_PROVISIONER_IMAGE,
                        LOCAL_PATH_HELPER_IMAGE,
                    )
                }
    }

    private lateinit var client: KubernetesClient
    private lateinit var templates: TemplateService

    private val bucket = "portable-placement-${UUID.randomUUID().toString().take(8)}"
    private val state =
        ClusterState(
            name = "portable",
            versions = mutableMapOf(),
            s3Bucket = bucket,
            initConfig = InitConfig(region = SharedLocalStack.region(), tenant = TENANT),
            hosts =
                mapOf(
                    ServerType.Control to
                        listOf(ClusterHost(publicIp = "", privateIp = "10.0.0.1", alias = "control0", availabilityZone = "a")),
                ),
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
        client
            .serviceAccounts()
            .inNamespace(NAMESPACE)
            .withName("default")
            .waitUntilCondition({ it != null }, EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val node =
            client
                .nodes()
                .list()
                .items
                .single()
        client.nodes().withName(node.metadata.name).edit { it.apply { metadata.labels.remove(CONTROL_PLANE_LABEL) } }

        val stateManager = mock<ClusterStateManager>()
        whenever(stateManager.load()).thenReturn(state)
        templates = TemplateService(stateManager, mock<User>())

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
                .withData<String, String>(ClusterConfigData.of(state.getControlHost()!!, state, SharedLocalStack.region()))
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
                    GrafanaManifestBuilder(templates).buildAllResources(DocumentsBucket(bucket, SharedLocalStack.region()), hosting)
            ).map(::toLocalStack)
    }

    /** [resource], with a backend configuration's S3 endpoint and credentials pointed at LocalStack. */
    private fun toLocalStack(resource: HasMetadata): HasMetadata {
        if (resource !is ConfigMap) return resource
        val edit: ((String) -> String)? =
            when (resource.metadata.name) {
                "mimir-config" -> ObservabilityBackends::mimirConfig
                "loki-config" -> ObservabilityBackends::lokiConfig
                "tempo-config" -> ObservabilityBackends::tempoConfig
                "pyroscope-config" -> ::pyroscopeConfig
                else -> null
            }
        return when (edit) {
            null -> resource
            else ->
                ConfigMapBuilder(resource)
                    .withData<String, String>(resource.data.mapValues { (_, content) -> inCluster(edit(content)) })
                    .build()
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
}
