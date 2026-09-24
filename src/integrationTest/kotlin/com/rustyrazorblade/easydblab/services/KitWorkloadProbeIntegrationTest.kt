package com.rustyrazorblade.easydblab.services

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.K3sDiagnostics.withClusterDiagnostics
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.kubernetes.DefaultKubernetesService
import com.rustyrazorblade.easydblab.kubernetes.ProxiedKubernetesClientFactory
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Runs [KitWorkloadProbe] against a real K3s cluster: the start-phase collision check must find
 * exactly the pods a kit's runtime selector matches, in the runtime's namespace, including pods that
 * are still being deleted: starting over a pod that is cleaning up would reapply onto that state.
 *
 * Pods are created as API objects only; whether their image ever runs does not matter here. Each
 * test uses its own kit name so the tests share one cluster without seeing each other's pods.
 *
 * The pod image is still preloaded into K3s and never pulled: a Deployment's pods are deleted by the
 * garbage collector, and a pod stuck pulling from a registry makes that deletion slow for reasons
 * outside the test. A failed wait here carries the cluster's pod and event state.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KitWorkloadProbeIntegrationTest {
    companion object {
        private const val OTHER_NAMESPACE = "elsewhere"
        private const val WAIT_SECONDS = 60L
        private const val IMPATIENT_POLLS = 3
        private const val NAMESPACE = "default"
        private const val POD_IMAGE = "registry.k8s.io/pause:3.9"

        @Container
        @JvmStatic
        val k3s: K3sContainer =
            K3sContainer(DockerImageName.parse("rancher/k3s:v1.30.6-k3s1"))
                .withPrivilegedMode(true)
                .withCreateContainerCmdModifier { cmd ->
                    cmd.hostConfig!!
                        .withCgroupnsMode("host")
                        .withUlimits(listOf(Ulimit("nofile", 65536L, 65536L)))
                }.withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("k3s")))
                .withEnv("K3S_SNAPSHOTTER", "native")
                .let { (it as K3sContainer).withPreloadedImages(POD_IMAGE) }
    }

    private lateinit var client: KubernetesClient
    private lateinit var probe: KitWorkloadProbe
    private lateinit var kubeService: DefaultKubernetesService

    private val controlHost = ClusterHost(publicIp = "", privateIp = "10.0.0.1", alias = "control0", availabilityZone = "us-west-2a")

    @BeforeAll
    fun setup() {
        client = KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        val kubeconfig = Files.createTempFile("kit-workload-probe", ".kubeconfig")
        Files.writeString(kubeconfig, k3s.kubeConfigYaml)
        // The helm branch runs helm on the control node over SSH; no runtime here is helm-backed.
        kubeService = DefaultKubernetesService(ProxiedKubernetesClientFactory(), kubeconfig)
        probe = KitWorkloadProbe(kubeService, mock(), pollInterval = Duration.ofSeconds(1), maxPolls = WAIT_SECONDS.toInt())

        client
            .resource(
                NamespaceBuilder()
                    .withNewMetadata()
                    .withName(OTHER_NAMESPACE)
                    .endMetadata()
                    .build(),
            ).create()
        for (namespace in listOf("default", OTHER_NAMESPACE)) {
            // Pod admission needs the namespace's default ServiceAccount, which K3s creates asynchronously.
            client
                .serviceAccounts()
                .inNamespace(namespace)
                .withName("default")
                .waitUntilCondition({ it != null }, WAIT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun podsRuntime(kitName: String) =
        KitRuntime(type = KitRuntime.RuntimeType.PODS, selector = "easydblab/kit=$kitName", namespace = "default")

    private fun createPod(
        name: String,
        labels: Map<String, String>,
        namespace: String = "default",
        finalizers: List<String> = emptyList(),
    ): Pod =
        client
            .resource(
                PodBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .withLabels<String, String>(labels)
                    .withFinalizers(finalizers)
                    .endMetadata()
                    .withNewSpec()
                    .addNewContainer()
                    .withName("pause")
                    .withImage(POD_IMAGE)
                    // Preloaded into K3s; never pulling makes a missing preload fail fast, not flake.
                    .withImagePullPolicy("Never")
                    .endContainer()
                    .endSpec()
                    .build(),
            ).create()

    @Test
    fun `finds nothing when no pod carries the runtime selector`() {
        createPod("other-kit-0", mapOf("easydblab/kit" to "not-probed"))

        assertThat(probe.find("absent", podsRuntime("absent"), controlHost).getOrThrow())
            .isEqualTo(WorkloadPresence.Absent)
    }

    @Test
    fun `finds the pods the runtime selector matches`() {
        createPod("running-0", mapOf("easydblab/kit" to "running"))

        assertThat(probe.find("running", podsRuntime("running"), controlHost).getOrThrow())
            .isEqualTo(WorkloadPresence.Present(namespace = "default", resources = listOf("pod/running-0")))
    }

    @Test
    fun `only looks in the runtime's namespace`() {
        createPod("scoped-0", mapOf("easydblab/kit" to "scoped"), namespace = OTHER_NAMESPACE)

        assertThat(probe.find("scoped", podsRuntime("scoped"), controlHost).getOrThrow())
            .isEqualTo(WorkloadPresence.Absent)
    }

    @Test
    fun `counts pods that are still being deleted`() {
        // A finalizer holds the deleted pod in Terminating, as a slow manual `kubectl delete` would.
        createPod("stopping-0", mapOf("easydblab/kit" to "stopping"), finalizers = listOf("easydblab.test/hold"))
        client
            .pods()
            .inNamespace("default")
            .withName("stopping-0")
            .delete()
        client
            .pods()
            .inNamespace("default")
            .withName("stopping-0")
            .waitUntilCondition({ it?.metadata?.deletionTimestamp != null }, WAIT_SECONDS, TimeUnit.SECONDS)

        assertThat(probe.find("stopping", podsRuntime("stopping"), controlHost).getOrThrow())
            .isEqualTo(WorkloadPresence.Present(namespace = "default", resources = listOf("pod/stopping-0")))
    }

    @Test
    fun `a kit with no runtime block is found by its app kubernetes io name label`() {
        createPod("bare-0", mapOf("app.kubernetes.io/name" to "bare"))

        assertThat(probe.find("bare", runtime = null, controlHost = controlHost).getOrThrow())
            .isEqualTo(WorkloadPresence.Present(namespace = "default", resources = listOf("pod/bare-0")))
    }

    /** Waits until no pod of [kitName] is left; a timeout carries the cluster's pod and event state. */
    private fun awaitGone(kitName: String) {
        withClusterDiagnostics(client, NAMESPACE) {
            assertThat(probe.awaitGone(kitName, podsRuntime(kitName), controlHost).getOrThrow())
                .isEqualTo(WorkloadPresence.Absent)
        }
    }

    private fun deletePod(name: String) {
        client
            .pods()
            .inNamespace("default")
            .withName(name)
            .delete()
        client
            .pods()
            .inNamespace("default")
            .withName(name)
            .waitUntilCondition({ it?.metadata?.deletionTimestamp != null }, WAIT_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `waiting for a stopped kit counts a pod still terminating, and ends once it is gone`() {
        createPod("draining-0", mapOf("easydblab/kit" to "draining"), finalizers = listOf("easydblab.test/hold"))
        deletePod("draining-0")
        val impatient = KitWorkloadProbe(kubeService, mock(), pollInterval = Duration.ZERO, maxPolls = IMPATIENT_POLLS)

        assertThat(impatient.awaitGone("draining", podsRuntime("draining"), controlHost).getOrThrow())
            .isEqualTo(WorkloadPresence.Present(namespace = "default", resources = listOf("pod/draining-0")))

        client
            .pods()
            .inNamespace("default")
            .withName("draining-0")
            .edit { pod -> pod.apply { metadata.finalizers = emptyList() } }

        awaitGone("draining")
    }

    /**
     * The stop-then-start race: deleting a Deployment returns before the garbage collector has even
     * marked its pods for deletion. Waiting ends only when no pod of the kit is left at all.
     */
    @Test
    fun `waiting for a kit whose Deployment was just deleted ends only when its pods are gone`() {
        val labels = mapOf("easydblab/kit" to "deployed")
        client
            .resource(
                DeploymentBuilder()
                    .withNewMetadata()
                    .withName("deployed")
                    .withNamespace("default")
                    .endMetadata()
                    .withNewSpec()
                    .withReplicas(2)
                    .withNewSelector()
                    .withMatchLabels<String, String>(labels)
                    .endSelector()
                    .withNewTemplate()
                    .withNewMetadata()
                    .withLabels<String, String>(labels)
                    .endMetadata()
                    .withNewSpec()
                    .withTerminationGracePeriodSeconds(0L)
                    .addNewContainer()
                    .withName("pause")
                    .withImage(POD_IMAGE)
                    // Preloaded into K3s; never pulling makes a missing preload fail fast, not flake.
                    .withImagePullPolicy("Never")
                    .endContainer()
                    .endSpec()
                    .endTemplate()
                    .endSpec()
                    .build(),
            ).create()
        client
            .pods()
            .inNamespace("default")
            .withLabel("easydblab/kit", "deployed")
            .informOnCondition { it.size == 2 }
            .let { pods -> withClusterDiagnostics(client, NAMESPACE) { pods.get(WAIT_SECONDS, TimeUnit.SECONDS) } }

        client
            .apps()
            .deployments()
            .inNamespace("default")
            .withName("deployed")
            .delete()

        awaitGone("deployed")
        assertThat(
            client
                .pods()
                .inNamespace("default")
                .withLabel("easydblab/kit", "deployed")
                .list()
                .items,
        ).isEmpty()
    }
}
