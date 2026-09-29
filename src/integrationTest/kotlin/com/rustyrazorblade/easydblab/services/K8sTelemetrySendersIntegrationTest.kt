package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.K3sDiagnostics.clusterDiagnostics
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.PatchContext
import io.fabric8.kubernetes.client.dsl.base.PatchType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Proves [K8sTelemetrySenders] — how the pre-teardown save stops the OTel collector — against a real
 * K3s cluster, with the DaemonSet the cluster deploys ([OtelManifestBuilder]).
 *
 * The DaemonSet is applied to its own namespace with its ServiceAccount, so its pod is created. The
 * collector image is not preloaded: its pod never starts, which does not matter to the stop, whose
 * contract is only that no collector pod is left. A pod that never started would be gone at once, so
 * the test holds it in Terminating with a finalizer it removes only after a delay: the stop must
 * still be waiting then.
 */
class K8sTelemetrySendersIntegrationTest : BaseKoinTest() {
    private companion object {
        const val NAMESPACE = "telemetry-senders"
        const val NAME_LABEL = "app.kubernetes.io/name"
        const val POD_WAIT_SECONDS = 120L
        const val POLL_MILLIS = 250L
        const val FINALIZER = "easydblab.com/test-hold"
        val TIMEOUT: Duration = Duration.ofSeconds(90)
        val HOLD: Duration = Duration.ofSeconds(5)
    }

    private val controlHost = ClusterHost("1.2.3.4", "10.0.0.1", "control0", "us-west-2a", instanceId = "i-test")
    private lateinit var client: KubernetesClient
    private lateinit var senders: K8sTelemetrySenders

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(
                            it.load(),
                        ).thenReturn(ClusterState(name = "senders-it", versions = mutableMapOf()))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        SharedK3s.createNamespace(NAMESPACE)
        client = SharedK3s.client()
        val clientProvider = mock<K8sClientProvider>()
        // Each stop closes the client it is handed, so every call gets a fresh one.
        whenever(clientProvider.createClient(any())).thenAnswer { SharedK3s.client() }
        senders = K8sTelemetrySenders(clientProvider, NAMESPACE, pollInterval = Duration.ofMillis(POLL_MILLIS))
    }

    @AfterEach
    fun tearDown() {
        releaseCollectorPods()
        client.close()
    }

    /** Holds every collector pod: deleted, it stays Terminating until [releaseCollectorPods]. */
    private fun holdCollectorPods() = patchCollectorPods("""{"metadata":{"finalizers":["$FINALIZER"]}}""")

    private fun releaseCollectorPods() = patchCollectorPods("""{"metadata":{"${'$'}deleteFromPrimitiveList/finalizers":["$FINALIZER"]}}""")

    /**
     * Applies [patch] to every collector pod as a strategic merge patch. The patch carries no
     * resourceVersion, so it does not conflict with the DaemonSet controller and kubelet updating
     * the pod at the same moment, as an edit (a read, then an update) did.
     */
    private fun patchCollectorPods(patch: String) =
        collectorPods().forEach { pod ->
            client
                .pods()
                .inNamespace(NAMESPACE)
                .withName(pod.metadata.name)
                .patch(PatchContext.of(PatchType.STRATEGIC_MERGE), patch)
        }

    private fun collectorPods() =
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withLabel(NAME_LABEL, Constants.K8s.OTEL_COLLECTOR_APP_LABEL)
            .list()
            .items

    /** Applies the cluster's collector DaemonSet and ServiceAccount to [NAMESPACE] and waits for its pod. */
    private fun deployCollector() {
        val builder = OtelManifestBuilder(getKoin().get())
        val serviceAccount =
            ServiceAccountBuilder(
                builder.buildServiceAccount(),
            ).editMetadata().withNamespace(NAMESPACE).endMetadata().build()
        val daemonSet =
            DaemonSetBuilder(builder.buildDaemonSet())
                .editMetadata()
                .withNamespace(NAMESPACE)
                .endMetadata()
                .build()
        listOf<HasMetadata>(serviceAccount, daemonSet).forEach { client.resource(it).inNamespace(NAMESPACE).createOr { r -> r.update() } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(POD_WAIT_SECONDS)
        while (collectorPods().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
        }
        assertThat(collectorPods()).describedAs(clusterDiagnostics(client, NAMESPACE)).isNotEmpty()
    }

    @Test
    fun `stopping deletes the collector DaemonSet and returns once its pods are gone, and succeeds again once it is gone`() {
        deployCollector()
        holdCollectorPods()
        val releasedAt = AtomicLong()
        val releaser =
            Thread {
                Thread.sleep(HOLD.toMillis())
                releasedAt.set(System.nanoTime())
                releaseCollectorPods()
            }.apply { start() }

        senders.stop(controlHost, TIMEOUT)
        val stoppedAt = System.nanoTime()
        releaser.join()

        // The pod stayed Terminating until the release, and the stop was still waiting for it.
        assertThat(releasedAt.get()).describedAs("the pod was released while the stop waited").isNotZero()
        assertThat(stoppedAt).describedAs("the stop returned after the pod was released").isGreaterThan(releasedAt.get())
        assertThat(collectorPods()).isEmpty()
        assertThat(
            client
                .apps()
                .daemonSets()
                .inNamespace(NAMESPACE)
                .withName(Constants.K8s.OTEL_COLLECTOR_APP_LABEL)
                .get(),
        ).isNull()

        // A re-run of down finds the collector already gone: the stop still succeeds.
        senders.stop(controlHost, TIMEOUT)

        assertThat(collectorPods()).isEmpty()
    }
}
