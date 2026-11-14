package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.BackendStorage
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.Placement
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.grafana.BackendUrls
import com.rustyrazorblade.easydblab.configuration.grafana.DocumentsBucket
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDatasourceSet
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.TimeUnit

/**
 * The observability backends and Grafana deploy onto a cluster that has no node labelled as the
 * control plane, as an environment easy-db-lab did not build would have: with an empty
 * [Placement] and their state on claims, every one is scheduled, in the pod network, and Grafana's
 * datasources name each backend by its Service.
 *
 * The [SharedK3s] node carries the control-plane label K3s gives a server; this test removes it for
 * its own run and puts it back afterwards. The workloads go into a namespace of their own.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortableObservabilityPlacementIntegrationTest {
    companion object {
        private const val NAMESPACE = "portable-placement"
        private const val CONTROL_PLANE_LABEL = "node-role.kubernetes.io/control-plane"

        /** K3s's bundled provisioner, standing in for #1035's retained StorageClass. */
        private const val LOCAL_PATH_CLASS = "local-path"
        private const val SCHEDULE_TIMEOUT_SECONDS = 180L
    }

    private lateinit var client: KubernetesClient
    private lateinit var templates: TemplateService
    private var removedLabel: String? = null

    private val hosting =
        ObservabilityHosting.AWS.copy(
            placement = Placement.ANY_NODE,
            storage = BackendStorage.RetainedClaim(LOCAL_PATH_CLASS, Quantity("1Gi")),
        )

    @BeforeAll
    fun setup() {
        SharedK3s.createNamespace(NAMESPACE)
        client = SharedK3s.client()
        val node =
            client
                .nodes()
                .list()
                .items
                .single()
        removedLabel = node.metadata.labels[CONTROL_PLANE_LABEL]
        client.nodes().withName(node.metadata.name).edit { it.apply { metadata.labels.remove(CONTROL_PLANE_LABEL) } }

        val stateManager = mock<ClusterStateManager>()
        whenever(stateManager.load()).thenReturn(ClusterState(name = "test", versions = mutableMapOf(), s3Bucket = "acct-bucket"))
        templates = TemplateService(stateManager, mock<User>())
    }

    @AfterAll
    fun tearDown() {
        val node =
            client
                .nodes()
                .list()
                .items
                .single()
        removedLabel?.let { value ->
            client.nodes().withName(node.metadata.name).edit { it.apply { metadata.labels[CONTROL_PLANE_LABEL] = value } }
        }
        client.namespaces().withName(NAMESPACE).delete()
        client.close()
    }

    private fun resources(): List<HasMetadata> =
        (
            MimirManifestBuilder(templates).buildAllResources(hosting) +
                LokiManifestBuilder(templates).buildAllResources(hosting) +
                TempoManifestBuilder(templates).buildAllResources(hosting) +
                PyroscopeManifestBuilder(templates).buildServerResources(hosting) +
                GrafanaManifestBuilder(templates).buildAllResources(DocumentsBucket("acct-bucket", "us-west-2"), hosting)
        ).onEach { it.metadata.namespace = NAMESPACE }

    private fun scheduled(app: String): Pod =
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withLabel("app.kubernetes.io/name", app)
            .waitUntilCondition(
                { pod ->
                    pod
                        ?.status
                        ?.conditions
                        .orEmpty()
                        .any { it.type == "PodScheduled" && it.status == "True" }
                },
                SCHEDULE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )

    @Test
    fun `every backend and Grafana is scheduled on a node with no control-plane label, in the pod network`() {
        assertThat(
            client
                .nodes()
                .list()
                .items
                .single()
                .metadata.labels,
        ).doesNotContainKey(CONTROL_PLANE_LABEL)

        resources().forEach { client.resource(it).serverSideApply() }

        listOf("mimir", "loki", "tempo", "pyroscope", "grafana").forEach { app ->
            val pod = scheduled(app)
            assertThat(pod.spec.hostNetwork ?: false).describedAs(app).isFalse()
            assertThat(pod.spec.nodeSelector.orEmpty()).describedAs(app).isEmpty()
            assertThat(pod.spec.volumes.mapNotNull { it.persistentVolumeClaim?.claimName }).describedAs(app).isNotEmpty()
        }
    }

    @Test
    fun `Grafana's datasources name each backend by its Service, which exists`() {
        resources().forEach { client.resource(it).serverSideApply() }
        val services =
            client
                .services()
                .inNamespace(NAMESPACE)
                .list()
                .items
                .map { it.metadata.name }
        val urls = GrafanaDatasourceSet.build(TenantSet.of("default"), BackendUrls.IN_CLUSTER).datasources.mapNotNull { it.url }

        assertThat(urls).allSatisfy { assertThat(it).doesNotContain("localhost") }
        assertThat(services).contains("mimir", "loki", "tempo", "pyroscope")
        listOf(BackendUrls.IN_CLUSTER.metrics, BackendUrls.IN_CLUSTER.logs, BackendUrls.IN_CLUSTER.traces, BackendUrls.IN_CLUSTER.profiles)
            .forEach { url -> assertThat(services).contains(url.removePrefix("http://").substringBefore('.')) }
    }
}
