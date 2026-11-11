package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.K3sDiagnostics.clusterDiagnostics
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * Proves [DefaultK8sNamespaceOperations.waitForRollouts] against a real K3s cluster.
 *
 * The bug it guards: after a rollout-restart the old pods stay Ready while the replacements start,
 * so `grafana update-config` reported "All observability pods are ready" with restarted pods still
 * at 0/1. Each workload here has a readiness probe with an initial delay, so a replacement pod is
 * deliberately not Ready for a few seconds after it starts. `waitForRollouts` must not return until
 * the replacement is Ready and the old pod is gone.
 *
 * The pod image is preloaded into K3s and never pulled, so a registry stall cannot eat the wait's
 * budget; a timeout here is the rollout's, and its message carries the cluster's pod and event state.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RolloutWaitIntegrationTest {
    companion object {
        private const val NAMESPACE = "rollout-wait"
        private const val IMAGE = SharedK3s.BUSYBOX_IMAGE
        private const val READY_DELAY_SECONDS = 5
        private const val TIMEOUT_SECONDS = 180
        private const val SHORT_TIMEOUT_SECONDS = 8
    }

    private val controlHost =
        ClusterHost(
            publicIp = "1.2.3.4",
            privateIp = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test",
        )

    private lateinit var client: KubernetesClient
    private lateinit var ops: DefaultK8sNamespaceOperations

    @BeforeAll
    fun setup() {
        SharedK3s.createNamespace(NAMESPACE)
        client = SharedK3s.client()
        val clientProvider = mock<K8sClientProvider>()
        // Each operation closes the client it is handed, so every call gets a fresh one.
        whenever(clientProvider.createClient(any())).thenAnswer { SharedK3s.client() }
        ops = DefaultK8sNamespaceOperations(clientProvider, EventBus(), podPollInterval = Duration.ofMillis(500))
    }

    @AfterAll
    fun tearDown() {
        client.close()
    }

    private fun podSpec(readinessCommand: String): PodSpec =
        PodSpecBuilder()
            .addNewContainer()
            .withName("main")
            .withImage(IMAGE)
            // Preloaded into K3s; never pulling makes a missing preload fail fast, not flake.
            .withImagePullPolicy("Never")
            .withCommand("sh", "-c", "sleep 3600")
            .withNewReadinessProbe()
            .withNewExec()
            .withCommand("sh", "-c", readinessCommand)
            .endExec()
            .withInitialDelaySeconds(READY_DELAY_SECONDS)
            .withPeriodSeconds(1)
            .endReadinessProbe()
            .endContainer()
            .withTerminationGracePeriodSeconds(0)
            .build()

    private fun createDeployment(
        name: String,
        readinessCommand: String = "true",
    ) {
        val deployment =
            DeploymentBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .addToMatchLabels("app", name)
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .addToLabels("app", name)
                .endMetadata()
                .withSpec(podSpec(readinessCommand))
                .endTemplate()
                .endSpec()
                .build()
        client.resource(deployment).create()
    }

    private fun createDaemonSet(name: String) {
        val daemonSet =
            DaemonSetBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .withNewSelector()
                .addToMatchLabels("app", name)
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .addToLabels("app", name)
                .endMetadata()
                .withSpec(podSpec("true"))
                .endTemplate()
                .endSpec()
                .build()
        client.resource(daemonSet).create()
    }

    /** Names of the app's pods that are not terminating, paired with whether each is Ready. */
    private fun livePods(app: String): Map<String, Boolean> =
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withLabel("app", app)
            .list()
            .items
            .filter { it.metadata.deletionTimestamp == null }
            .associate { pod ->
                pod.metadata.name to (pod.status?.conditions?.any { it.type == "Ready" && it.status == "True" } == true)
            }

    /**
     * Waits for [ref] to roll out, and on failure rethrows with the cluster's node, pod and event
     * state appended, so a timeout says whether the pod was unscheduled, pulling, or unready.
     */
    private fun awaitRollout(ref: WorkloadRef) {
        ops.waitForRollouts(controlHost, listOf(ref), NAMESPACE, TIMEOUT_SECONDS).getOrElse { e ->
            throw AssertionError("${e.message}\n${clusterDiagnostics(client, NAMESPACE)}", e)
        }
    }

    @Test
    fun `waits until a restarted Deployment's replacement pod is ready and the old pod is gone`() {
        val name = "rollout-deploy"
        val ref = WorkloadRef(WorkloadKind.Deployment, name)
        createDeployment(name)
        awaitRollout(ref)
        val before = livePods(name).keys
        assertThat(before).hasSize(1)

        ops.rolloutRestartDeployment(controlHost, name, NAMESPACE).getOrThrow()
        awaitRollout(ref)

        val after = livePods(name)
        assertThat(after).hasSize(1)
        assertThat(after.keys).doesNotContainAnyElementsOf(before)
        assertThat(after.values).containsOnly(true)
    }

    @Test
    fun `waits until a restarted DaemonSet's replacement pod is ready`() {
        val name = "rollout-ds"
        val ref = WorkloadRef(WorkloadKind.DaemonSet, name)
        createDaemonSet(name)
        awaitRollout(ref)
        val before = livePods(name).keys
        assertThat(before).hasSize(1)

        ops.rolloutRestartDaemonSet(controlHost, name, NAMESPACE).getOrThrow()
        awaitRollout(ref)

        val after = livePods(name)
        assertThat(after).hasSize(1)
        assertThat(after.keys).doesNotContainAnyElementsOf(before)
        assertThat(after.values).containsOnly(true)
    }

    @Test
    fun `fails naming the workload when its rollout does not finish within the timeout`() {
        val name = "rollout-never-ready"
        createDeployment(name, readinessCommand = "false")

        val result =
            ops.waitForRollouts(
                controlHost,
                listOf(WorkloadRef(WorkloadKind.Deployment, name)),
                NAMESPACE,
                SHORT_TIMEOUT_SECONDS,
            )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Timed out after ${SHORT_TIMEOUT_SECONDS}s")
            .hasMessageContaining("Deployment/$name")
            .hasMessageContaining("0 of 1 updated replicas are available")
    }
}
