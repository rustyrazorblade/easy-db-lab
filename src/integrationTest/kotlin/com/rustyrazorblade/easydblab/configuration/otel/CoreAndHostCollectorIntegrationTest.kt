package com.rustyrazorblade.easydblab.configuration.otel

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.K3sDiagnostics.clusterDiagnostics
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apps.DaemonSet
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
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit

/**
 * The two collectors as the cluster deploys them, in a real K3s cluster.
 *
 * The core is rendered with [PodLogSource.None], as for an environment that grants no hostPath: it
 * must reach Ready unprivileged, as a non-root user, with no host volume, host port or host
 * network. The host add-on then runs beside it, and what it collects must reach the core through
 * the `otel-collector` Service. The core's own receiver counters show it: the add-on is the only
 * OTLP sender in this cluster. No backend runs, because only the hop from add-on to core is under
 * test; the core's exports fail, which does not touch its receiver counters.
 */
class CoreAndHostCollectorIntegrationTest : BaseKoinTest() {
    private companion object {
        const val CLUSTER = "two-collectors-lab"
        const val READY_SECONDS = 240L
        const val ARRIVAL_ATTEMPTS = 60
        const val POLL_MILLIS = 2000L
        const val NAME_LABEL = "app.kubernetes.io/name"
        const val SELF_TELEMETRY_PORT = 8888
        const val COLLECTOR_IMAGE = "otel/opentelemetry-collector-contrib:${Constants.OtelCollector.VERSION}"
        val ACCEPTED_FROM_OTLP = Regex("""otelcol_receiver_accepted_metric_points(_total)?\{[^}]*receiver="otlp"[^}]*}\s+([0-9.e+]+)""")
    }

    private val state = ClusterState(name = CLUSTER, versions = mutableMapOf())
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
                    checkNotNull(cmd.hostConfig)
                        .withCgroupnsMode("host")
                        .withUlimits(listOf(Ulimit("nofile", 65536L, 65536L)))
                }.withEnv("K3S_SNAPSHOTTER", "native")
                .withPreloadedImages(COLLECTOR_IMAGE)
                .also { it.start() }
        k3s = container
        return KubernetesClientBuilder().withConfig(Config.fromKubeconfig(container.kubeConfigYaml)).build()
    }

    private fun clusterConfig() =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName("cluster-config")
            .endMetadata()
            .addToData("cluster_name", CLUSTER)
            .addToData("tenant", "default")
            .build()

    private fun apply(
        client: KubernetesClient,
        resources: List<HasMetadata>,
    ) = resources.forEach { client.resource(it).inNamespace(Constants.K8s.NAMESPACE).create() }

    private fun awaitReady(
        client: KubernetesClient,
        daemonSet: String,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_SECONDS)
        while (readyPods(client, daemonSet) != 1) {
            check(System.nanoTime() < deadline) {
                "$daemonSet never became ready\n${clusterDiagnostics(client, Constants.K8s.NAMESPACE)}"
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun readyPods(
        client: KubernetesClient,
        daemonSet: String,
    ): Int? =
        client
            .apps()
            .daemonSets()
            .inNamespace(Constants.K8s.NAMESPACE)
            .withName(daemonSet)
            .get()
            ?.status
            ?.numberReady

    /** The OTLP metric points the core has accepted, read from its own telemetry on 8888. */
    private fun acceptedOtlpPoints(client: KubernetesClient): Double {
        val core =
            client
                .pods()
                .inNamespace(Constants.K8s.NAMESPACE)
                .withLabel(NAME_LABEL, Constants.K8s.OTEL_COLLECTOR_APP_LABEL)
                .list()
                .items
                .single()
        return client
            .pods()
            .inNamespace(Constants.K8s.NAMESPACE)
            .withName(core.metadata.name)
            .portForward(SELF_TELEMETRY_PORT)
            .use { forward ->
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${forward.localPort}/metrics")).build()
                val body = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body()
                ACCEPTED_FROM_OTLP.findAll(body).sumOf { it.groupValues[2].toDouble() }
            }
    }

    private fun awaitAcceptedOtlpPoints(client: KubernetesClient): Double {
        var accepted = 0.0
        repeat(ARRIVAL_ATTEMPTS) {
            if (accepted == 0.0) {
                Thread.sleep(POLL_MILLIS)
                accepted = acceptedOtlpPoints(client)
            }
        }
        return accepted
    }

    @Test
    fun `the restricted core reaches Ready and receives what the host add-on forwards through its Service`() {
        startK3s().use { client ->
            client.resource(clusterConfig()).inNamespace(Constants.K8s.NAMESPACE).create()
            val core = OtelManifestBuilder(getKoin().get())
            apply(client, core.buildAllResources(NodeCapabilities.LOCAL_DOCKER, podLogSource = PodLogSource.None))
            awaitReady(client, Constants.K8s.OTEL_COLLECTOR_APP_LABEL)

            val running =
                client
                    .apps()
                    .daemonSets()
                    .inNamespace(Constants.K8s.NAMESPACE)
                    .withName(Constants.K8s.OTEL_COLLECTOR_APP_LABEL)
                    .get()
            assertRestricted(running)
            assertThat(acceptedOtlpPoints(client)).describedAs("nothing sends OTLP before the add-on").isZero()

            apply(client, OtelHostCollectorManifestBuilder(getKoin().get()).buildAllResources(CniMode.Flannel))
            awaitReady(client, Constants.OtelCollector.HOST_COLLECTOR_NAME)

            assertThat(awaitAcceptedOtlpPoints(client))
                .describedAs("the core's OTLP receiver counted the add-on's host metrics")
                .isPositive()
        }
    }

    private fun assertRestricted(daemonSet: DaemonSet) {
        val pod = daemonSet.spec.template.spec
        val context = pod.containers.single().securityContext
        assertThat(pod.hostNetwork == true).describedAs("hostNetwork").isFalse()
        assertThat(pod.volumes).noneMatch { it.hostPath != null }
        assertThat(pod.containers.single().ports).allMatch { it.hostPort == null }
        assertThat(context.privileged == true).describedAs("privileged").isFalse()
        assertThat(context.runAsNonRoot == true).describedAs("runAsNonRoot").isTrue()
        assertThat(context.allowPrivilegeEscalation == true).describedAs("allowPrivilegeEscalation").isFalse()
        assertThat(context.capabilities.drop).containsExactly("ALL")
        assertThat(context.capabilities.add.orEmpty()).isEmpty()
    }
}
