package com.rustyrazorblade.easydblab.configuration.otel

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.K3sDiagnostics.clusterDiagnostics
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit

/**
 * A node with no systemd journal still delivers a pod's stdout to Loki, through the collector's
 * container-log pipeline alone. That is what lets the journald reader be skipped on such a node
 * (the `SYSTEMD_JOURNAL` capability).
 *
 * The K3s container has no `/var/log/journal`, so a line that arrives cannot have come from
 * Fluent Bit. The collector is the real DaemonSet from [OtelManifestBuilder]. Loki is the pinned
 * image on filesystem storage, because the S3 wiring is not what is under test.
 */
class PodLogsWithoutJournalIntegrationTest : BaseKoinTest() {
    private companion object {
        const val CLUSTER = "no-journal-lab"
        const val WORKLOAD_NAMESPACE = "marker"
        const val MARKER = "marker-line-from-a-pod-without-a-journal"
        const val READY_SECONDS = 240L
        const val ARRIVAL_ATTEMPTS = 90
        const val POLL_MILLIS = 2000L
        const val COLLECTOR_IMAGE = "otel/opentelemetry-collector-contrib:${Constants.OtelCollector.VERSION}"
        val LOKI_CONFIG =
            """
            auth_enabled: false
            server:
              http_listen_port: 3100
            common:
              path_prefix: /loki
              replication_factor: 1
              ring:
                instance_addr: 127.0.0.1
                kvstore:
                  store: inmemory
              storage:
                filesystem:
                  chunks_directory: /loki/chunks
                  rules_directory: /loki/rules
            limits_config:
              allow_structured_metadata: true
              otlp_config:
                resource_attributes:
                  attributes_config:
                    - action: index_label
                      attributes: [cluster]
            schema_config:
              configs:
                - from: "2024-01-01"
                  store: tsdb
                  object_store: filesystem
                  schema: v13
                  index:
                    prefix: index_
                    period: 24h
            """.trimIndent()
    }

    private val state = ClusterState(name = CLUSTER, versions = mutableMapOf(), s3Bucket = "easy-db-lab-test")
    private var k3s: K3sContainer? = null

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<ClusterStateManager>().also { whenever(it.load()).thenReturn(state) } }
                single { TemplateService(get(), get()) }
            },
        )

    @AfterEach
    fun stopK3s() {
        k3s?.stop()
    }

    private fun startK3s(): KubernetesClient {
        val container =
            K3sContainer(DockerImageName.parse(SharedK3s.K3S_IMAGE))
                .withPrivilegedMode(true)
                .withCreateContainerCmdModifier { cmd ->
                    cmd.hostConfig!!
                        .withCgroupnsMode("host")
                        .withUlimits(listOf(Ulimit("nofile", 65536L, 65536L)))
                }.withEnv("K3S_SNAPSHOTTER", "native")
                .withPreloadedImages(SharedK3s.BUSYBOX_IMAGE, COLLECTOR_IMAGE, LokiManifestBuilder.IMAGE)
                .also { it.start() }
        k3s = container
        return KubernetesClientBuilder().withConfig(Config.fromKubeconfig(container.kubeConfigYaml)).build()
    }

    private fun lokiResources(): List<HasMetadata> = listOf(lokiConfigMap(), lokiDeployment(), lokiService())

    private fun lokiLabels() = mapOf("app.kubernetes.io/name" to Constants.K8s.LOKI_APP_LABEL)

    private fun lokiConfigMap() =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName("loki-test-config")
            .endMetadata()
            .addToData("loki.yaml", LOKI_CONFIG)
            .build()

    private fun lokiDeployment() =
        DeploymentBuilder()
            .withNewMetadata()
            .withName(Constants.K8s.LOKI_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withReplicas(1)
            .withNewSelector()
            .addToMatchLabels(lokiLabels())
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels(lokiLabels())
            .endMetadata()
            .withSpec(lokiPodSpec())
            .endTemplate()
            .endSpec()
            .build()

    private fun lokiPodSpec() =
        PodSpecBuilder()
            .addNewContainer()
            .withName(Constants.K8s.LOKI_APP_LABEL)
            .withImage(LokiManifestBuilder.IMAGE)
            .withArgs("-config.file=/etc/loki/loki.yaml")
            .addNewPort()
            .withContainerPort(Constants.K8s.LOKI_HTTP_PORT)
            .endPort()
            .addNewVolumeMount()
            .withName("config")
            .withMountPath("/etc/loki")
            .endVolumeMount()
            .addNewVolumeMount()
            .withName("data")
            .withMountPath("/loki")
            .endVolumeMount()
            .endContainer()
            .addNewVolume()
            .withName("config")
            .withNewConfigMap()
            .withName("loki-test-config")
            .endConfigMap()
            .endVolume()
            .addNewVolume()
            .withName("data")
            .withNewEmptyDir()
            .endEmptyDir()
            .endVolume()
            .build()

    private fun lokiService() =
        ServiceBuilder()
            .withNewMetadata()
            .withName(Constants.K8s.LOKI_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .addToSelector(lokiLabels())
            .addNewPort()
            .withPort(Constants.K8s.LOKI_HTTP_PORT)
            .endPort()
            .endSpec()
            .build()

    private fun clusterConfig() =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName("cluster-config")
            .endMetadata()
            .addToData("cluster_name", CLUSTER)
            .addToData("tenant", "default")
            .build()

    private fun markerPod() =
        PodBuilder()
            .withNewMetadata()
            .withName("marker")
            .withNamespace(WORKLOAD_NAMESPACE)
            .endMetadata()
            .withNewSpec()
            .addNewContainer()
            .withName("marker")
            .withImage(SharedK3s.BUSYBOX_IMAGE)
            .withImagePullPolicy("Never")
            // The collector tails from the end of each file, so keep writing until it is found.
            .withCommand("sh", "-c", "while true; do echo $MARKER; sleep 1; done")
            .endContainer()
            .endSpec()
            .build()

    private fun awaitReady(
        client: KubernetesClient,
        what: String,
        ready: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_SECONDS)
        while (!ready()) {
            check(System.nanoTime() < deadline) { "$what never became ready\n${clusterDiagnostics(client, "default")}" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** Loki is up, with the cluster-config ConfigMap the collector reads. */
    private fun deployLoki(client: KubernetesClient) {
        lokiResources().forEach { client.resource(it).inNamespace("default").create() }
        client.resource(clusterConfig()).inNamespace("default").create()
        awaitReady(client, "Loki") {
            client
                .apps()
                .deployments()
                .inNamespace("default")
                .withName("loki")
                .get()
                ?.status
                ?.readyReplicas == 1
        }
    }

    /** The real collector DaemonSet, rendered for an environment with no capabilities. */
    private fun deployCollector(client: KubernetesClient) {
        OtelManifestBuilder(getKoin().get()).buildAllResources(NodeCapabilities.LOCAL_DOCKER).forEach {
            client.resource(it).inNamespace("default").create()
        }
        awaitReady(client, "the collector") {
            client
                .apps()
                .daemonSets()
                .inNamespace("default")
                .withName("otel-collector")
                .get()
                ?.status
                ?.numberReady == 1
        }
    }

    /** A pod in its own namespace that prints [MARKER] until it is found. */
    private fun startMarkerPod(client: KubernetesClient) {
        client
            .resource(
                NamespaceBuilder()
                    .withNewMetadata()
                    .withName(WORKLOAD_NAMESPACE)
                    .endMetadata()
                    .build(),
            ).create()
        awaitReady(client, "the default ServiceAccount") {
            client
                .serviceAccounts()
                .inNamespace(WORKLOAD_NAMESPACE)
                .withName("default")
                .get() != null
        }
        client.resource(markerPod()).create()
    }

    /** Loki's answer to a query for [MARKER], polled until it appears or the attempts run out. */
    private fun awaitMarkerInLoki(client: KubernetesClient): String {
        val lokiPod =
            client
                .pods()
                .inNamespace("default")
                .withLabel("app.kubernetes.io/name", "loki")
                .list()
                .items
                .single()
        return client
            .pods()
            .inNamespace("default")
            .withName(lokiPod.metadata.name)
            .portForward(Constants.K8s.LOKI_HTTP_PORT)
            .use { forward -> pollLoki(forward.localPort) }
    }

    private fun pollLoki(port: Int): String {
        val query = URLEncoder.encode("{cluster=\"$CLUSTER\"} |= \"$MARKER\"", Charsets.UTF_8)
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/loki/api/v1/query_range?query=$query&since=10m")).build()
        val http = HttpClient.newHttpClient()
        var body = ""
        repeat(ARRIVAL_ATTEMPTS) {
            if (!body.contains(MARKER)) {
                Thread.sleep(POLL_MILLIS)
                body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
            }
        }
        return body
    }

    @Test
    fun `a pod's stdout line reaches Loki through the collector on a node with no journal`() {
        startK3s().use { client ->
            deployLoki(client)
            deployCollector(client)
            startMarkerPod(client)

            val body = awaitMarkerInLoki(client)

            assertThat(body).describedAs("Loki's answer; the node has no /var/log/journal").contains(MARKER)
        }
    }
}
