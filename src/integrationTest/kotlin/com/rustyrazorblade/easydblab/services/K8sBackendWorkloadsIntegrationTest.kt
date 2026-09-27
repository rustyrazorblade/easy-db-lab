package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.K3sDiagnostics.clusterDiagnostics
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Proves [K8sBackendWorkloads] — how the pre-teardown flush stops Loki and Mimir — against a real
 * K3s cluster.
 *
 * Each backend is stood in for by a busybox Deployment named after it and labelled
 * `app.kubernetes.io/name=<backend>`, as the real ones are. Its pod ignores SIGTERM and has a
 * termination grace period, so a scale-down that stopped waiting before the pod was gone would be
 * seen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class K8sBackendWorkloadsIntegrationTest {
    private companion object {
        const val NAMESPACE = "backend-workloads"
        const val NAME_LABEL = "app.kubernetes.io/name"
        const val GRACE_SECONDS = 8L
        const val READY_TIMEOUT_SECONDS = 120L
        const val CLEANUP_POLL_MILLIS = 250L
        val TIMEOUT: Duration = Duration.ofSeconds(90)
        val SHORT_TIMEOUT: Duration = Duration.ofSeconds(3)
    }

    private val controlHost = ClusterHost("1.2.3.4", "10.0.0.1", "control0", "us-west-2a", instanceId = "i-test")
    private lateinit var client: KubernetesClient
    private lateinit var workloads: K8sBackendWorkloads

    @BeforeAll
    fun setup() {
        SharedK3s.createNamespace(NAMESPACE)
        client = SharedK3s.client()
        val clientProvider = mock<K8sClientProvider>()
        // Each action closes the client it is handed, so every call gets a fresh one.
        whenever(clientProvider.createClient(any())).thenAnswer { SharedK3s.client() }
        workloads = K8sBackendWorkloads(clientProvider, NAMESPACE, pollInterval = Duration.ofMillis(250))
    }

    @AfterEach
    fun removeDeployments() {
        client
            .apps()
            .deployments()
            .inNamespace(NAMESPACE)
            .delete()
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withGracePeriod(0)
            .delete()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS)
        while (client
                .pods()
                .inNamespace(NAMESPACE)
                .list()
                .items
                .isNotEmpty() &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(CLEANUP_POLL_MILLIS)
        }
    }

    @AfterAll
    fun tearDown() {
        client.close()
    }

    /** A one-replica Deployment [name], ready once its container runs. */
    private fun backend(name: String): Deployment =
        DeploymentBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(NAMESPACE)
            .endMetadata()
            .withNewSpec()
            .withReplicas(1)
            .withNewSelector()
            .addToMatchLabels(NAME_LABEL, name)
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels(NAME_LABEL, name)
            .endMetadata()
            .withNewSpec()
            .withTerminationGracePeriodSeconds(GRACE_SECONDS)
            .addNewContainer()
            .withName(name)
            .withImage(SharedK3s.BUSYBOX_IMAGE)
            // Preloaded into K3s; never pulling makes a missing preload fail fast, not flake.
            .withImagePullPolicy("Never")
            // PID 1 ignores SIGTERM, so the pod lives out its grace period when it is deleted.
            .withCommand("sh", "-c", "trap '' TERM; while true; do sleep 1; done")
            .withNewReadinessProbe()
            .withNewExec()
            .withCommand("true")
            .endExec()
            .withPeriodSeconds(1)
            .endReadinessProbe()
            .endContainer()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()

    private fun deploy(deployment: Deployment) {
        client.resource(deployment).create()
    }

    private fun deployReady(name: String) {
        deploy(backend(name))
        runCatching {
            client
                .apps()
                .deployments()
                .inNamespace(NAMESPACE)
                .withName(name)
                .waitUntilReady(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.getOrElse { throw AssertionError("$name never became ready\n${clusterDiagnostics(client, NAMESPACE)}", it) }
    }

    private fun pods(name: String): List<Pod> =
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withLabel(NAME_LABEL, name)
            .list()
            .items

    private fun Pod.isReady(): Boolean =
        metadata.deletionTimestamp == null &&
            status?.conditions.orEmpty().any { it.type == "Ready" && it.status == "True" }

    @Test
    fun `scaling down returns once the pod is gone, and the Deployment asks for no replica`() {
        deployReady("loki")

        workloads.scaleDown(controlHost, "loki", TIMEOUT)

        assertThat(pods("loki")).isEmpty()
        assertThat(
            client
                .apps()
                .deployments()
                .inNamespace(NAMESPACE)
                .withName("loki")
                .get()
                .spec.replicas,
        ).isZero()
    }

    @Test
    fun `only the named backend is acted on`() {
        deployReady("loki")
        deployReady("mimir")
        val mimir = pods("mimir").single().metadata.uid

        workloads.scaleDown(controlHost, "loki", TIMEOUT)

        assertThat(pods("loki")).isEmpty()
        assertThat(pods("mimir").map { it.metadata.uid }).containsExactly(mimir)
    }

    @Test
    fun `a pod that outlives the scale-down wait fails it`() {
        // The pod ignores SIGTERM for its grace period, which is longer than this wait.
        deployReady("loki")

        assertThatThrownBy { workloads.scaleDown(controlHost, "loki", SHORT_TIMEOUT) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("loki was not gone within ${SHORT_TIMEOUT.seconds}s")
    }
}
