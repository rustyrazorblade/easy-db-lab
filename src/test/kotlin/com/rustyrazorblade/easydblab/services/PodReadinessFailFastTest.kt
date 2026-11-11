package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimList
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodList
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation
import io.fabric8.kubernetes.client.dsl.PodResource
import io.fabric8.kubernetes.client.dsl.Resource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * The namespace readiness wait decides on each poll whether a pod failed for good. That decision
 * must end the wait at once: a failure that is thrown inside the poll is retried like a transient
 * error until the deadline, which at 1,000 nodes is over half an hour.
 *
 * The Kubernetes client is stubbed at its API, answering with fixed pod lists; the K3s test
 * (`PodReadinessWaitIntegrationTest`) runs the same wait against a real unpullable image.
 */
class PodReadinessFailFastTest {
    private val host = ClusterHost("1.2.3.4", "10.0.0.1", "control0", "us-west-2a", "i-1")

    private fun pod(waitingReason: String?): Pod =
        PodBuilder()
            .withNewMetadata()
            .withName("web")
            .endMetadata()
            .withNewStatus()
            .withPhase("Pending")
            .addNewContainerStatus()
            .withName("web")
            .withNewState()
            .withNewWaiting()
            .withReason(waitingReason)
            .withMessage("image not found")
            .endWaiting()
            .endState()
            .endContainerStatus()
            .endStatus()
            .build()

    private fun opsListing(pods: List<Pod>): DefaultK8sNamespaceOperations {
        val podsInNamespace = mock<NonNamespaceOperation<Pod, PodList, PodResource>>()
        whenever(podsInNamespace.list()).thenReturn(PodList().apply { items = pods })
        val allPods = mock<MixedOperation<Pod, PodList, PodResource>>()
        whenever(allPods.inNamespace("ns")).thenReturn(podsInNamespace)
        val claimsInNamespace =
            mock<NonNamespaceOperation<PersistentVolumeClaim, PersistentVolumeClaimList, Resource<PersistentVolumeClaim>>>()
        whenever(claimsInNamespace.list()).thenReturn(PersistentVolumeClaimList())
        val allClaims = mock<MixedOperation<PersistentVolumeClaim, PersistentVolumeClaimList, Resource<PersistentVolumeClaim>>>()
        whenever(allClaims.inNamespace("ns")).thenReturn(claimsInNamespace)
        val client = mock<KubernetesClient>()
        whenever(client.pods()).thenReturn(allPods)
        whenever(client.persistentVolumeClaims()).thenReturn(allClaims)
        val provider = mock<K8sClientProvider>()
        whenever(provider.createClient(any())).thenReturn(client)
        return DefaultK8sNamespaceOperations(provider, EventBus(), podPollInterval = Duration.ofMillis(POLL_MS))
    }

    @Test
    fun `a pod in ImagePullBackOff fails the wait at once with its reason`() {
        val ops = opsListing(listOf(pod("ImagePullBackOff")))

        val started = System.nanoTime()
        val result = ops.waitForPodsReady(host, TIMEOUT_SECONDS, "ns")
        val waited = Duration.ofNanos(System.nanoTime() - started)

        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("web")
            .hasMessageContaining("ImagePullBackOff")
            .hasMessageContaining("image not found")
        assertThat(waited).isLessThan(Duration.ofSeconds(FAIL_FAST_LIMIT_SECONDS))
    }

    @Test
    fun `a pod still creating its container times out naming its state`() {
        val ops = opsListing(listOf(pod("ContainerCreating")))

        val result = ops.waitForPodsReady(host, 1, "ns")

        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Timed out")
            .hasMessageContaining("web")
    }

    private companion object {
        const val POLL_MS = 20L
        const val TIMEOUT_SECONDS = 15
        const val FAIL_FAST_LIMIT_SECONDS = 3L
    }
}
