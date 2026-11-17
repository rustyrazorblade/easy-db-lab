package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.K3sDiagnostics.clusterDiagnostics
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.util.concurrent.TimeUnit

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

        /** A broken pod must end the wait this long before [TIMEOUT_SECONDS], or it waited it out. */
        private const val FAIL_FAST_LIMIT_SECONDS = 90L

        /** An image on a reserved TLD (RFC 2606) that no registry can serve. */
        private const val UNPULLABLE_IMAGE = "registry.invalid/easy-db-lab/no-such-image:1"
    }

    private lateinit var client: KubernetesClient
    private lateinit var ops: DefaultK8sNamespaceOperations

    /** Every workload a test created, deleted after it so the shared cluster keeps no broken pods. */
    private val created = mutableListOf<WorkloadRef>()

    @BeforeAll
    fun setup() {
        SharedK3s.createNamespace(NAMESPACE)
        client = SharedK3s.client()
        // Each operation closes the client it is handed, so every call gets a fresh one.
        val clientProvider = WorkspaceK8s.provider(SharedK3s.kubeConfigYaml())
        ops = DefaultK8sNamespaceOperations(clientProvider, EventBus(), podPollInterval = Duration.ofMillis(500))
    }

    @AfterEach
    fun deleteWorkloads() {
        created.forEach { ref ->
            when (ref.kind) {
                WorkloadKind.Deployment ->
                    client
                        .apps()
                        .deployments()
                        .inNamespace(NAMESPACE)
                        .withName(ref.name)
                        .delete()
                WorkloadKind.DaemonSet ->
                    client
                        .apps()
                        .daemonSets()
                        .inNamespace(NAMESPACE)
                        .withName(ref.name)
                        .delete()
            }
        }
        created.clear()
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
        spec: PodSpec = podSpec(readinessCommand),
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
                .withSpec(spec)
                .endTemplate()
                .endSpec()
                .build()
        client.resource(deployment).create()
        created += WorkloadRef(WorkloadKind.Deployment, name)
    }

    private fun createDaemonSet(
        name: String,
        spec: PodSpec = podSpec("true"),
    ) {
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
                .withSpec(spec)
                .endTemplate()
                .endSpec()
                .build()
        client.resource(daemonSet).create()
        created += WorkloadRef(WorkloadKind.DaemonSet, name)
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
        ops.waitForRollouts(listOf(ref), NAMESPACE, TIMEOUT_SECONDS).getOrElse { e ->
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

        ops.rolloutRestartDeployment(name, NAMESPACE).getOrThrow()
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

        ops.rolloutRestartDaemonSet(name, NAMESPACE).getOrThrow()
        awaitRollout(ref)

        val after = livePods(name)
        assertThat(after).hasSize(1)
        assertThat(after.keys).doesNotContainAnyElementsOf(before)
        assertThat(after.values).containsOnly(true)
    }

    /** A pod spec whose container exits at once, so the kubelet puts it in CrashLoopBackOff. */
    private fun crashingSpec(): PodSpec =
        PodSpecBuilder(podSpec("true"))
            .editFirstContainer()
            .withCommand("sh", "-c", "exit 1")
            .endContainer()
            .build()

    /** Waits until a pod of [app] has a container in CrashLoopBackOff, so a fix lands on broken pods. */
    private fun awaitCrashLoop(app: String) {
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withLabel("app", app)
            .informOnCondition { pods -> pods.any { K8sPodUtils.failureOf(it)?.contains("CrashLoopBackOff") == true } }
            .get(FAIL_FAST_LIMIT_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * Waits on [workloads] with the long [TIMEOUT_SECONDS] budget and asserts it failed well before
     * it, with a message naming [ref], a pod of it, the node and [reason].
     */
    private fun assertFailsFast(
        ref: WorkloadRef,
        reason: String,
        workloads: List<WorkloadRef> = listOf(ref),
    ) {
        val started = System.nanoTime()
        val result = ops.waitForRollouts(workloads, NAMESPACE, TIMEOUT_SECONDS)
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertThat(result.isFailure).isTrue()
        assertThat(elapsed).isLessThan(Duration.ofSeconds(FAIL_FAST_LIMIT_SECONDS))
        val nodeName =
            client
                .nodes()
                .list()
                .items
                .single()
                .metadata.name
        assertThat(result.exceptionOrNull())
            .hasMessageContaining(ref.toString())
            .hasMessageContaining("Pod ${ref.name}-")
            .hasMessageContaining("node $nodeName")
            .hasMessageContaining(reason)
    }

    @Test
    fun `fails at once naming the pod when a DaemonSet's image cannot be pulled`() {
        val name = "rollout-bad-image"
        val spec =
            PodSpecBuilder(podSpec("true"))
                .editFirstContainer()
                .withImage(UNPULLABLE_IMAGE)
                .withImagePullPolicy("Always")
                .endContainer()
                .build()
        createDaemonSet(name, spec)

        // ErrImagePull and ImagePullBackOff alternate; whichever the poll sees first ends the wait.
        assertFailsFast(WorkloadRef(WorkloadKind.DaemonSet, name), "ImagePull")
    }

    @Test
    fun `fails at once naming the pod when a DaemonSet's container exits at once`() {
        val name = "rollout-crash"
        createDaemonSet(name, crashingSpec())

        assertFailsFast(WorkloadRef(WorkloadKind.DaemonSet, name), "CrashLoopBackOff")
    }

    @Test
    fun `fails at once naming the pod when a Deployment's container exits at once`() {
        val name = "rollout-deploy-crash"
        createDeployment(name, spec = crashingSpec())

        assertFailsFast(WorkloadRef(WorkloadKind.Deployment, name), "CrashLoopBackOff")
    }

    @Test
    fun `fails naming the broken workload when it is waited on with a healthy one`() {
        val healthy = "rollout-mixed-healthy"
        val broken = "rollout-mixed-broken"
        createDaemonSet(healthy)
        createDeployment(broken, spec = crashingSpec())
        val brokenRef = WorkloadRef(WorkloadKind.Deployment, broken)

        assertFailsFast(brokenRef, "CrashLoopBackOff", listOf(WorkloadRef(WorkloadKind.DaemonSet, healthy), brokenRef))
    }

    @Test
    fun `a fixed template replacing a crash-looping Deployment rolls out`() {
        val name = "rollout-deploy-fixed"
        createDeployment(name, spec = crashingSpec())
        awaitCrashLoop(name)

        client
            .apps()
            .deployments()
            .inNamespace(NAMESPACE)
            .withName(name)
            .edit {
                DeploymentBuilder(it)
                    .editSpec()
                    .editTemplate()
                    .withSpec(podSpec("true"))
                    .endTemplate()
                    .endSpec()
                    .build()
            }
        awaitRollout(WorkloadRef(WorkloadKind.Deployment, name))

        assertThat(livePods(name).values).containsExactly(true)
    }

    @Test
    fun `a fixed template replacing a crash-looping DaemonSet rolls out`() {
        val name = "rollout-ds-fixed"
        createDaemonSet(name, crashingSpec())
        awaitCrashLoop(name)

        client
            .apps()
            .daemonSets()
            .inNamespace(NAMESPACE)
            .withName(name)
            .edit {
                DaemonSetBuilder(it)
                    .editSpec()
                    .editTemplate()
                    .withSpec(podSpec("true"))
                    .endTemplate()
                    .endSpec()
                    .build()
            }
        awaitRollout(WorkloadRef(WorkloadKind.DaemonSet, name))

        assertThat(livePods(name).values).containsExactly(true)
    }

    @Test
    fun `fails naming the workload when its rollout does not finish within the timeout`() {
        val name = "rollout-never-ready"
        createDeployment(name, readinessCommand = "false")

        val result =
            ops.waitForRollouts(
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
