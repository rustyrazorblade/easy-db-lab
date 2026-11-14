package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.K3sDiagnostics.clusterDiagnostics
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.otel.OtelHostCollectorManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.ServiceAccount
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.PatchContext
import io.fabric8.kubernetes.client.dsl.base.PatchType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Proves [K8sTelemetrySenders] — how the pre-teardown save stops the OTel collectors — against a real
 * K3s cluster, with the DaemonSets the cluster deploys ([OtelManifestBuilder], [OtelHostCollectorManifestBuilder]).
 *
 * The DaemonSet is applied to its own namespace with its ServiceAccount, so its pod is created. The
 * collector image is not preloaded: its pod never starts, which does not matter to the stop, whose
 * contract is only that no collector pod is left. A pod that never started would be gone at once, so
 * the test holds it in Terminating with a finalizer it removes only after a delay: the stop must
 * still be waiting then.
 */
class K8sTelemetrySendersIntegrationTest : BaseKoinTest() {
    private companion object {
        const val NAMESPACE_PREFIX = "telemetry-senders"
        val runs = AtomicInteger()
        const val NAME_LABEL = "app.kubernetes.io/name"
        const val POD_WAIT_SECONDS = 120L
        const val POLL_MILLIS = 250L
        const val FINALIZER = "easydblab.com/test-hold"
        val TIMEOUT: Duration = Duration.ofSeconds(90)
        val HOLD: Duration = Duration.ofSeconds(5)

        /** A deadline short enough to run out in the test. */
        val SHORT_TIMEOUT: Duration = Duration.ofSeconds(8)

        /** Most of [SHORT_TIMEOUT], so a second, fresh deadline would end well past [SLACK]. */
        val ADD_ON_HOLD: Duration = Duration.ofSeconds(6)

        /** A poll, the delete and the API calls after the deadline; well under [ADD_ON_HOLD]. */
        val SLACK: Duration = Duration.ofSeconds(4)
    }

    private lateinit var client: KubernetesClient
    private lateinit var senders: K8sTelemetrySenders

    /** Each test has its own namespace on the shared cluster, so one test's collectors never meet another's. */
    private val namespace = "$NAMESPACE_PREFIX-${runs.incrementAndGet()}"

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
        SharedK3s.createNamespace(namespace)
        client = SharedK3s.client()
        val clientProvider = mock<K8sClientProvider>()
        // Each stop closes the client it is handed, so every call gets a fresh one.
        whenever(clientProvider.createClient()).thenAnswer { SharedK3s.client() }
        senders = K8sTelemetrySenders(clientProvider, namespace, pollInterval = Duration.ofMillis(POLL_MILLIS))
    }

    @AfterEach
    fun tearDown() {
        TelemetrySenders.COLLECTORS.forEach { releaseCollectorPods(it) }
        client.close()
    }

    /** Holds every pod of collector [name]: deleted, it stays Terminating until [releaseCollectorPods]. */
    private fun holdCollectorPods(name: String = Constants.K8s.OTEL_COLLECTOR_APP_LABEL) =
        patchCollectorPods(name, """{"metadata":{"finalizers":["$FINALIZER"]}}""")

    private fun releaseCollectorPods(name: String = Constants.K8s.OTEL_COLLECTOR_APP_LABEL) =
        patchCollectorPods(name, """{"metadata":{"${'$'}deleteFromPrimitiveList/finalizers":["$FINALIZER"]}}""")

    /**
     * Applies [patch] to every pod of collector [name] as a strategic merge patch. The patch carries
     * no resourceVersion, so it does not conflict with the DaemonSet controller and kubelet updating
     * the pod at the same moment, as an edit (a read, then an update) did.
     */
    private fun patchCollectorPods(
        name: String,
        patch: String,
    ) = collectorPods(name).forEach { pod ->
        client
            .pods()
            .inNamespace(namespace)
            .withName(pod.metadata.name)
            .patch(PatchContext.of(PatchType.STRATEGIC_MERGE), patch)
    }

    private fun collectorPods(name: String = Constants.K8s.OTEL_COLLECTOR_APP_LABEL) =
        client
            .pods()
            .inNamespace(namespace)
            .withLabel(NAME_LABEL, name)
            .list()
            .items

    /** Applies the cluster's core collector DaemonSet and ServiceAccount to [namespace] and waits for its pod. */
    private fun deployCollector() {
        val builder = OtelManifestBuilder(getKoin().get())
        deploy(Constants.K8s.OTEL_COLLECTOR_APP_LABEL, builder.buildServiceAccount(), builder.buildDaemonSet())
    }

    /** Applies the host add-on's DaemonSet and ServiceAccount to [namespace] and waits for its pod. */
    private fun deployHostCollector() {
        val builder = OtelHostCollectorManifestBuilder(getKoin().get())
        deploy(Constants.OtelCollector.HOST_COLLECTOR_NAME, builder.buildServiceAccount(), builder.buildDaemonSet())
    }

    private fun deploy(
        name: String,
        serviceAccount: ServiceAccount,
        daemonSet: DaemonSet,
    ) {
        val namespacedAccount =
            ServiceAccountBuilder(serviceAccount)
                .editMetadata()
                .withNamespace(namespace)
                .endMetadata()
                .build()
        val namespacedDaemonSet =
            DaemonSetBuilder(daemonSet)
                .editMetadata()
                .withNamespace(namespace)
                .endMetadata()
                .build()
        listOf<HasMetadata>(namespacedAccount, namespacedDaemonSet).forEach {
            client.resource(it).inNamespace(namespace).createOr { r ->
                r.update()
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(POD_WAIT_SECONDS)
        while (collectorPods(name).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
        }
        assertThat(collectorPods(name)).describedAs(clusterDiagnostics(client, namespace)).isNotEmpty()
    }

    private fun daemonSet(name: String): DaemonSet? =
        client
            .apps()
            .daemonSets()
            .inNamespace(namespace)
            .withName(name)
            .get()

    @Test
    fun `stopping with no host add-on deletes the core and returns once its pods are gone, and succeeds again once it is gone`() {
        deployCollector()
        holdCollectorPods()
        val releasedAt = AtomicLong()
        val releaser =
            Thread {
                Thread.sleep(HOLD.toMillis())
                releasedAt.set(System.nanoTime())
                releaseCollectorPods()
            }.apply { start() }

        senders.stop(TIMEOUT)
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
                .inNamespace(namespace)
                .withName(Constants.K8s.OTEL_COLLECTOR_APP_LABEL)
                .get(),
        ).isNull()

        // A re-run of down finds the collector already gone: the stop still succeeds.
        senders.stop(TIMEOUT)

        assertThat(collectorPods()).isEmpty()
    }

    /**
     * The add-on forwards to the core, so it is stopped first: the core's DaemonSet is still there
     * while an add-on pod is Terminating, and goes only after the add-on's last pod is gone.
     */
    @Test
    fun `the host add-on is stopped and gone before the core is deleted`() {
        deployCollector()
        deployHostCollector()
        holdCollectorPods(Constants.OtelCollector.HOST_COLLECTOR_NAME)
        val coreWhileAddOnHeld = CopyOnWriteArrayList<Boolean>()
        val releaser =
            Thread {
                Thread.sleep(HOLD.toMillis())
                coreWhileAddOnHeld += daemonSet(Constants.K8s.OTEL_COLLECTOR_APP_LABEL) != null
                releaseCollectorPods(Constants.OtelCollector.HOST_COLLECTOR_NAME)
            }.apply { start() }

        senders.stop(TIMEOUT)
        releaser.join()

        assertThat(coreWhileAddOnHeld).describedAs("the core outlived the add-on's Terminating pod").containsExactly(true)
        for (name in TelemetrySenders.COLLECTORS) {
            assertThat(daemonSet(name)).describedAs(name).isNull()
            assertThat(collectorPods(name)).describedAs(name).isEmpty()
        }
    }

    /**
     * An add-on pod that never goes runs the deadline out: the stop fails at the deadline, names
     * the add-on, and leaves the core running, so its last batches are not lost with nowhere to go.
     */
    @Test
    fun `an add-on that outlives the deadline fails the stop at the deadline, and the core is not deleted`() {
        deployCollector()
        deployHostCollector()
        holdCollectorPods(Constants.OtelCollector.HOST_COLLECTOR_NAME)
        val deleting = CopyOnWriteArrayList<String>()

        val started = System.nanoTime()
        assertThatThrownBy { senders.stop(SHORT_TIMEOUT) { deleting += it } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(Constants.OtelCollector.HOST_COLLECTOR_NAME)
            .hasMessageNotContaining("${Constants.K8s.OTEL_COLLECTOR_APP_LABEL} still")
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertThat(elapsed).isBetween(SHORT_TIMEOUT, SHORT_TIMEOUT.plus(SLACK))
        assertThat(deleting).containsExactly(Constants.OtelCollector.HOST_COLLECTOR_NAME)
        assertThat(daemonSet(Constants.K8s.OTEL_COLLECTOR_APP_LABEL)).describedAs("the core's DaemonSet").isNotNull()
        assertThat(collectorPods()).describedAs("the core's pods").isNotEmpty().allMatch { it.metadata.deletionTimestamp == null }
    }

    /**
     * The deadline is shared: the time the add-on's wait took is not given back to the core. An
     * add-on released after most of the deadline, and a core that never goes, fail the stop at the
     * one deadline, naming the core, not a fresh timeout after the add-on's wait.
     */
    @Test
    fun `the core's wait gets only what the add-on left of the one deadline`() {
        deployCollector()
        deployHostCollector()
        holdCollectorPods(Constants.OtelCollector.HOST_COLLECTOR_NAME)
        holdCollectorPods()
        val releaser =
            Thread {
                Thread.sleep(ADD_ON_HOLD.toMillis())
                releaseCollectorPods(Constants.OtelCollector.HOST_COLLECTOR_NAME)
            }.apply { start() }

        val started = System.nanoTime()
        assertThatThrownBy { senders.stop(SHORT_TIMEOUT) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("${Constants.K8s.OTEL_COLLECTOR_APP_LABEL} still")
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        releaser.join()

        // A fresh deadline for the core would end at ADD_ON_HOLD + SHORT_TIMEOUT, past this bound.
        assertThat(elapsed).isBetween(SHORT_TIMEOUT, SHORT_TIMEOUT.plus(SLACK))
        assertThat(daemonSet(Constants.OtelCollector.HOST_COLLECTOR_NAME)).isNull()
        assertThat(collectorPods(Constants.OtelCollector.HOST_COLLECTOR_NAME)).isEmpty()
    }
}
