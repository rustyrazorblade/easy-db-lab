package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.K3sDiagnostics.clusterDiagnostics
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
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
import java.util.concurrent.TimeUnit

/**
 * Proves [DefaultK8sNamespaceOperations.waitForPodsReady] against a real K3s cluster.
 *
 * The bug it guards: the observability readiness gate waits on every pod in the namespace, and a
 * kit's stress Job leaves a pod there in phase Succeeded, or Failed when the run failed. A finished
 * Job pod is never Ready, so `grafana update-config` and `up` timed out on it after applying a
 * healthy stack.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PodReadinessWaitIntegrationTest {
    companion object {
        private const val NAMESPACE = "readiness-wait"
        private const val IMAGE = SharedK3s.BUSYBOX_IMAGE
        private const val JOB_TIMEOUT_SECONDS = 180L
        private const val WAIT_TIMEOUT_SECONDS = 20
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

    private fun job(
        name: String,
        command: String,
    ): Job =
        JobBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(NAMESPACE)
            .endMetadata()
            .withNewSpec()
            .withBackoffLimit(0)
            .withNewTemplate()
            .withNewSpec()
            .withRestartPolicy("Never")
            .addNewContainer()
            .withName(name)
            .withImage(IMAGE)
            // Preloaded into K3s; never pulling makes a missing preload fail fast, not flake.
            .withImagePullPolicy("Never")
            .withCommand("sh", "-c", command)
            .endContainer()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()

    /** Runs a Job to its end, as [finished] reports it, and returns the phases of its pods. */
    private fun runJob(
        name: String,
        command: String,
        finished: (Job?) -> Boolean,
    ): List<String?> {
        client.resource(job(name, command)).create()
        client
            .batch()
            .v1()
            .jobs()
            .inNamespace(NAMESPACE)
            .withName(name)
            .waitUntilCondition(finished, JOB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return client
            .pods()
            .inNamespace(NAMESPACE)
            .withLabel("job-name", name)
            .list()
            .items
            .map { it.status?.phase }
    }

    private fun awaitNamespaceReady() {
        ops.waitForPodsReady(controlHost, WAIT_TIMEOUT_SECONDS, NAMESPACE).getOrElse { e ->
            throw AssertionError("${e.message}\n${clusterDiagnostics(client, NAMESPACE)}", e)
        }
    }

    @Test
    fun `a pod that ran to completion does not hold up the namespace readiness wait`() {
        val phases = runJob("stress", "true") { it?.status?.succeeded == 1 }
        assertThat(phases).containsExactly(K8sPodUtils.SUCCEEDED_PHASE)

        awaitNamespaceReady()
    }

    @Test
    fun `a Job pod that failed does not hold up the namespace readiness wait`() {
        val phases = runJob("stress-failed", "exit 1") { it?.status?.failed == 1 }
        assertThat(phases).containsExactly(K8sPodUtils.FAILED_PHASE)

        awaitNamespaceReady()
    }
}
