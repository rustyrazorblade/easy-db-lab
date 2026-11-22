package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.NodeSelector
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardTreeWriter
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.configuration.stress.StressCluster
import com.rustyrazorblade.easydblab.configuration.stress.StressJobManifestBuilder
import com.rustyrazorblade.easydblab.configuration.stress.StressSidecarConfig
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import com.rustyrazorblade.easydblab.configuration.tenancy.OwnershipLabels
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.aws.BucketRegion
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The create paths that are not a typed apply (a stress Job, the stress sidecar ConfigMap,
 * `grafana-datasources`, a metrics registry ConfigMap) leave live objects that carry both ownership
 * labels when they go through the service the CLI runs with, [DefaultK8sService.owned], so a sweep by
 * the cluster label finds them (design D6).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnedCreatePathsIntegrationTest {
    private companion object {
        const val NAMESPACE = "owned-creates"
        const val CLUSTER_ID = "owned-create-cluster"
    }

    private val scope = InstallScope(K8sNamespace(NAMESPACE), CLUSTER_ID)
    private val state =
        ClusterState(
            name = "owned",
            clusterId = CLUSTER_ID,
            s3Bucket = "easy-db-lab-test",
            initConfig = InitConfig(region = "us-west-2", name = "owned", namespace = NAMESPACE),
        )

    private lateinit var client: KubernetesClient
    private lateinit var k8sService: DefaultK8sService
    private lateinit var templates: TemplateService

    @BeforeAll
    fun setup() {
        SharedK3s.createNamespace(NAMESPACE)
        client = SharedK3s.client()
        k8sService = DefaultK8sService.owned(WorkspaceK8s.provider(SharedK3s.kubeConfigYaml()), EventBus()) { scope }
        val stateManager = mock<ClusterStateManager>()
        whenever(stateManager.load()).thenReturn(state)
        templates = TemplateService(stateManager)
    }

    @AfterAll
    fun teardown() {
        client.close()
    }

    private fun configMapLabels(name: String): Map<String, String> =
        client
            .configMaps()
            .inNamespace(NAMESPACE)
            .withName(name)
            .get()
            .metadata.labels

    @Test
    fun `a stress Job carries the ownership labels on its metadata only`() {
        val job =
            StressJobManifestBuilder.buildCommandJob(
                jobName = "owned-command",
                image = SharedK3s.BUSYBOX_IMAGE,
                args = listOf("true"),
                appSelector = NodeSelector(emptyMap()),
                namespace = scope.namespace,
            )

        k8sService.createJob(NAMESPACE, job).getOrThrow()

        val live =
            client
                .batch()
                .v1()
                .jobs()
                .inNamespace(NAMESPACE)
                .withName("owned-command")
                .get()
        assertThat(live.metadata.labels).containsAllEntriesOf(OwnershipLabels.of(CLUSTER_ID))
        assertThat(live.spec.template.metadata.labels).doesNotContainKeys(*OwnershipLabels.of(CLUSTER_ID).keys.toTypedArray())
    }

    @Test
    fun `the stress sidecar ConfigMap carries the ownership labels`() {
        StressSidecarConfig(templates, k8sService).ensureConfigMap(StressCluster.from(state))

        assertThat(configMapLabels(StressSidecarConfig.CONFIG_MAP_NAME)).containsAllEntriesOf(OwnershipLabels.of(CLUSTER_ID))
    }

    @Test
    fun `grafana-datasources carries the ownership labels`() {
        DefaultGrafanaDeployService(
            k8sService,
            GrafanaManifestBuilder(templates),
            GrafanaDashboardTreeWriter(GrafanaDashboardCatalog.discover()),
            EventBus(),
            ConfigChangeReport(k8sService, EventBus()),
            // The datasources ConfigMap does not ask the bucket's region.
            BucketRegion(mock()),
        ).createDatasourcesConfigMap(TenantSet.of("default"), scope).getOrThrow()

        assertThat(configMapLabels("grafana-datasources")).containsAllEntriesOf(OwnershipLabels.of(CLUSTER_ID))
    }

    @Test
    fun `a metrics registry ConfigMap carries the ownership labels`() {
        val otelSync = mock<OtelSyncService>()
        whenever(otelSync.syncConfigMap()).thenReturn(Result.success(Unit))

        DefaultMetricsRegistryService(k8sService, otelSync, EventBus()) { scope }
            .register("owned", listOf(KitMetrics.Scrape(port = 9363, path = "/metrics")))
            .getOrThrow()

        assertThat(configMapLabels("easydblab-metrics-owned-owned")).containsAllEntriesOf(OwnershipLabels.of(CLUSTER_ID))
    }
}
