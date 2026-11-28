package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.DirectReadWait
import com.rustyrazorblade.easydblab.K3sDiagnostics.withClusterDiagnostics
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.dsl.base.PatchContext
import io.fabric8.kubernetes.client.dsl.base.PatchType
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
import java.time.Duration

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
        private val WAIT: Duration = Duration.ofSeconds(WAIT_SECONDS)
        private const val IMPATIENT_POLLS = 3
        private const val HOLD_SECONDS = "3600"
        private const val NAMESPACE = "default"
        private const val POD_IMAGE = "registry.k8s.io/pause:3.9"

        @Container
        @JvmStatic
        val k3s: K3sContainer =
            SharedK3s
                .newContainer(POD_IMAGE, SharedK3s.BUSYBOX_IMAGE)
                .withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("k3s"))) as K3sContainer
    }

    private lateinit var client: KubernetesClient
    private lateinit var probe: KitWorkloadProbe
    private lateinit var kubeService: K8sService

    @BeforeAll
    fun setup() {
        client = KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        // The helm branch runs helm on the control node over SSH; no runtime here is helm-backed.
        kubeService = DefaultK8sService(WorkspaceK8s.provider(k3s.kubeConfigYaml), EventBus())
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
            SharedK3s.awaitServiceAccount(client, namespace) { k3s.logs }
        }
    }

    private fun podsRuntime(kitName: String) =
        KitRuntime(type = KitRuntime.RuntimeType.PODS, selector = "easydblab/kit=$kitName", namespace = "default")

    private fun createPod(
        name: String,
        labels: Map<String, String>,
        namespace: String = "default",
        finalizers: List<String> = emptyList(),
        runsWhileTerminating: Boolean = false,
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
                    .withImage(if (runsWhileTerminating) SharedK3s.BUSYBOX_IMAGE else POD_IMAGE)
                    // Preloaded into K3s; never pulling makes a missing preload fail fast, not flake.
                    .withImagePullPolicy("Never")
                    .apply {
                        // PID 1 ignores SIGTERM unless it handles it, so this container outlives the pod's delete.
                        if (runsWhileTerminating) withCommand("sleep", HOLD_SECONDS)
                    }.endContainer()
                    .withTerminationGracePeriodSeconds(if (runsWhileTerminating) HOLD_SECONDS.toLong() else null)
                    .endSpec()
                    .build(),
            ).create()

    /**
     * Waits until [name] runs. The kubelet stops the containers of a pod being deleted, and a pod whose
     * containers have all exited reaches a terminal phase, which the probe does not count. The tests that
     * delete a pod to see it counted while it terminates therefore keep its container running, and wait
     * for it to run before they delete it.
     */
    private fun awaitRunning(name: String) {
        DirectReadWait.awaitPod(client, DirectReadWait.PodRef(NAMESPACE, name), "is Running", WAIT) { it?.status?.phase == "Running" }
    }

    /** Waits until the API server has marked [name] for deletion. */
    private fun awaitDeletionMarked(name: String) {
        DirectReadWait.awaitPod(client, DirectReadWait.PodRef(NAMESPACE, name), "is marked for deletion", WAIT) {
            it?.metadata?.deletionTimestamp != null
        }
    }

    @Test
    fun `finds nothing when no pod carries the runtime selector`() {
        createPod("other-kit-0", mapOf("easydblab/kit" to "not-probed"))

        assertThat(probe.find("absent", podsRuntime("absent")).getOrThrow())
            .isEqualTo(WorkloadPresence.Absent)
    }

    @Test
    fun `finds the pods the runtime selector matches`() {
        createPod("running-0", mapOf("easydblab/kit" to "running"))

        assertThat(probe.find("running", podsRuntime("running")).getOrThrow())
            .isEqualTo(WorkloadPresence.Present(namespace = "default", resources = listOf("pod/running-0")))
    }

    @Test
    fun `only looks in the runtime's namespace`() {
        createPod("scoped-0", mapOf("easydblab/kit" to "scoped"), namespace = OTHER_NAMESPACE)

        assertThat(probe.find("scoped", podsRuntime("scoped")).getOrThrow())
            .isEqualTo(WorkloadPresence.Absent)
    }

    @Test
    fun `counts pods that are still being deleted`() {
        // A finalizer holds the deleted pod in Terminating, as a slow manual `kubectl delete` would.
        createPod(
            "stopping-0",
            mapOf("easydblab/kit" to "stopping"),
            finalizers = listOf("easydblab.test/hold"),
            runsWhileTerminating = true,
        )
        awaitRunning("stopping-0")
        client
            .pods()
            .inNamespace("default")
            .withName("stopping-0")
            .delete()
        awaitDeletionMarked("stopping-0")

        assertThat(probe.find("stopping", podsRuntime("stopping")).getOrThrow())
            .isEqualTo(WorkloadPresence.Present(namespace = "default", resources = listOf("pod/stopping-0")))
    }

    @Test
    fun `a kit with no runtime block is found by its app kubernetes io name label`() {
        createPod("bare-0", mapOf("app.kubernetes.io/name" to "bare"))

        assertThat(probe.find("bare", runtime = null).getOrThrow())
            .isEqualTo(WorkloadPresence.Present(namespace = "default", resources = listOf("pod/bare-0")))
    }

    /** Waits until no pod of [kitName] is left; a timeout carries the cluster's pod and event state. */
    private fun awaitGone(kitName: String) {
        withClusterDiagnostics(client, NAMESPACE) {
            assertThat(probe.awaitGone(kitName, podsRuntime(kitName)).getOrThrow())
                .isEqualTo(WorkloadPresence.Absent)
        }
    }

    private fun deletePod(name: String) {
        client
            .pods()
            .inNamespace("default")
            .withName(name)
            .delete()
        awaitDeletionMarked(name)
    }

    @Test
    fun `waiting for a stopped kit counts a pod still terminating, and ends once it is gone`() {
        createPod(
            "draining-0",
            mapOf("easydblab/kit" to "draining"),
            finalizers = listOf("easydblab.test/hold"),
            runsWhileTerminating = true,
        )
        awaitRunning("draining-0")
        deletePod("draining-0")
        val impatient = KitWorkloadProbe(kubeService, mock(), pollInterval = Duration.ZERO, maxPolls = IMPATIENT_POLLS)

        assertThat(impatient.awaitGone("draining", podsRuntime("draining")).getOrThrow())
            .isEqualTo(WorkloadPresence.Present(namespace = "default", resources = listOf("pod/draining-0")))

        client
            .pods()
            .inNamespace("default")
            .withName("draining-0")
            // A JSON patch, not edit(): the kubelet updates the terminating pod's status, and edit()
            // sends the resourceVersion it read, so it fails with 409 Conflict when that races.
            .patch(PatchContext.of(PatchType.JSON), """[{"op": "remove", "path": "/metadata/finalizers"}]""")
        // The container ignores SIGTERM, so the pod would stay for its whole grace period: end it now.
        client
            .pods()
            .inNamespace("default")
            .withName("draining-0")
            .withGracePeriod(0)
            .delete()

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
        withClusterDiagnostics(client, NAMESPACE) {
            val deployed = DirectReadWait.PodSelection(NAMESPACE, "easydblab/kit=deployed")
            DirectReadWait.awaitPods(client, deployed, "are 2 pods", WAIT) { it.size == 2 }
        }

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
