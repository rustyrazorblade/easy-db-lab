package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.JournaldOtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.registry.RegistryManifestBuilder
import com.rustyrazorblade.easydblab.configuration.s3manager.S3ManagerManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.victoria.VictoriaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.yace.YaceManifestBuilder
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapList
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.AnyNamespaceOperation
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests the two modes of [DefaultObservabilityStackService].
 *
 * Uses real manifest builders and TemplateService (never mock configuration classes) so the tests
 * assert on the actual resources the builders emit. The redirect branch is the reason this service
 * exists as a first-class code path, so both modes are exercised against the real builder output
 * rather than against counts.
 */
class ObservabilityStackServiceTest : BaseKoinTest() {
    private lateinit var mockK8sService: K8sService
    private lateinit var mockK8sClientProvider: K8sClientProvider
    private lateinit var mockRemoteOps: RemoteOperationsService
    private lateinit var mockDashboardService: GrafanaDashboardService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockK8sClient: KubernetesClient

    private lateinit var service: DefaultObservabilityStackService

    private val controlNode =
        ClusterHost(
            publicIp = "54.123.45.67",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test123",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<K8sService>().also { mockK8sService = it } }
                single { mock<K8sClientProvider>().also { mockK8sClientProvider = it } }
                single<RemoteOperationsService> { mock<RemoteOperationsService>().also { mockRemoteOps = it } }
                single { mock<GrafanaDashboardService>().also { mockDashboardService = it } }
                single { mock<ClusterStateManager>().also { mockClusterStateManager = it } }

                // Real TemplateService and manifest builders — never mock configuration classes.
                single { TemplateService(get(), get()) }
                single { BeylaManifestBuilder(get()) }
                single { EbpfExporterManifestBuilder() }
                single { JournaldOtelManifestBuilder(get()) }
                single { OtelManifestBuilder(get()) }
                single { PyroscopeManifestBuilder(get()) }
                single { TempoManifestBuilder(get()) }
                single { VictoriaManifestBuilder() }
                single { RegistryManifestBuilder() }
                single { S3ManagerManifestBuilder(get()) }
                single { YaceManifestBuilder(get()) }
            },
        )

    @BeforeEach
    fun setup() {
        mockK8sService = getKoin().get()
        mockK8sClientProvider = getKoin().get()
        mockRemoteOps = getKoin().get()
        mockDashboardService = getKoin().get()
        mockClusterStateManager = getKoin().get()

        mockK8sClient = mock()
        val mockConfigMapOps = mock<MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>>>()
        val mockAnyNsOps = mock<AnyNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>>>()
        val mockFiltered = mock<FilterWatchListDeletable<ConfigMap, ConfigMapList, Resource<ConfigMap>>>()
        val emptyConfigMapList = ConfigMapList().also { it.items = mutableListOf() }

        whenever(mockK8sClient.configMaps()).thenReturn(mockConfigMapOps)
        whenever(mockConfigMapOps.inAnyNamespace()).thenReturn(mockAnyNsOps)
        whenever(mockAnyNsOps.withLabel(any<String>(), any<String>())).thenReturn(mockFiltered)
        whenever(mockFiltered.list()).thenReturn(emptyConfigMapList)
        whenever(mockK8sClientProvider.createClient(any())).thenReturn(mockK8sClient)

        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "easy-db-lab-test",
            ),
        )

        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.applyResource(any(), any<HasMetadata>())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.rolloutRestartDeployment(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.rolloutRestartDaemonSet(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.waitForPodsReady(any(), any())).thenReturn(Result.success(Unit))
        whenever(mockDashboardService.uploadDashboards(any())).thenReturn(Result.success(Unit))

        service =
            DefaultObservabilityStackService(
                mockK8sService,
                mockK8sClientProvider,
                mockRemoteOps,
                mockClusterStateManager,
                getKoin().get(),
                getKoin().get(),
                mockDashboardService,
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
            )
    }

    /** Every applied resource as a "kind/name" string, in apply order. */
    private fun appliedKindNames(): List<String> {
        val captor = argumentCaptor<HasMetadata>()
        verify(mockK8sService, atLeastOnce()).applyResource(any(), captor.capture())
        return captor.allValues.map { "${it.kind}/${it.metadata?.name}" }
    }

    /** Every command passed to [RemoteOperationsService.executeRemotely]. */
    private fun remoteCommands(): List<String> {
        val captor = argumentCaptor<String>()
        verify(mockRemoteOps, atLeastOnce()).executeRemotely(any(), captor.capture(), any(), any())
        return captor.allValues
    }

    @Test
    fun `local mode deploys the in-cluster backends, dashboards, and on-node directories`() {
        service.deploy(controlNode, telemetryRedirect = null).getOrThrow()

        val kindNames = appliedKindNames()
        val names = kindNames.map { it.substringAfter("/") }

        // The four local telemetry backends are applied, and the Pyroscope server (not the agent) runs.
        assertThat(names).contains("victoriametrics", "victorialogs", "tempo")
        assertThat(kindNames).contains("Deployment/pyroscope")

        // Dashboards are uploaded only in local mode.
        verify(mockDashboardService).uploadDashboards(any())

        // Both on-node data directories are prepared over SSH.
        val commands = remoteCommands()
        assertThat(commands).anyMatch { it.contains("/mnt/db1/pyroscope") }
        assertThat(commands).anyMatch { it.contains(GrafanaManifestBuilder.GRAFANA_DATA_PATH) }
    }

    @Test
    fun `redirect mode skips local backends and dashboards but still runs the eBPF agent`() {
        val redirect = TelemetryRedirect.fromBaseHost("10.0.0.9")

        service.deploy(controlNode, telemetryRedirect = redirect).getOrThrow()

        val kindNames = appliedKindNames()
        val names = kindNames.map { it.substringAfter("/") }

        // None of the local backends nor the Pyroscope server are applied under redirect.
        assertThat(names).doesNotContain("victoriametrics", "victorialogs", "tempo")
        assertThat(kindNames).doesNotContain("Deployment/pyroscope")
        // The eBPF profiling agent still runs on every node, pointed at the external stack.
        assertThat(kindNames).contains("DaemonSet/pyroscope-ebpf")

        // No Grafana in redirect mode, so no dashboards uploaded.
        verify(mockDashboardService, never()).uploadDashboards(any())

        // Neither on-node directory (Pyroscope server data, Grafana data) is prepared: the server
        // and Grafana do not exist here, so redirect makes no SSH calls at all.
        verify(mockRemoteOps, never()).executeRemotely(any(), any(), any(), any())

        // Readiness is still gated on the applied collectors coming up.
        verify(mockK8sService).waitForPodsReady(any(), any())
    }
}
