package com.rustyrazorblade.easydblab.commands.grafana

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.JournaldOtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.registry.RegistryManifestBuilder
import com.rustyrazorblade.easydblab.configuration.s3manager.S3ManagerManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.victoria.VictoriaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.yace.YaceManifestBuilder
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import com.rustyrazorblade.easydblab.services.K8sClientProvider
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapList
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.AnyNamespaceOperation
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Test suite for GrafanaUpdateConfig command.
 *
 * Uses real manifest builders (never mock configuration classes) with
 * mocked K8sService to verify the full observability stack is applied.
 */
class GrafanaUpdateConfigTest : BaseKoinTest() {
    private lateinit var mockDashboardService: GrafanaDashboardService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockK8sService: K8sService
    private lateinit var mockK8sClientProvider: K8sClientProvider
    private lateinit var mockK8sClient: KubernetesClient

    private val testControlHost =
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
                single {
                    mock<GrafanaDashboardService>().also {
                        mockDashboardService = it
                    }
                }

                single {
                    mock<ClusterStateManager>().also {
                        mockClusterStateManager = it
                    }
                }

                single {
                    mock<K8sService>().also {
                        mockK8sService = it
                    }
                }

                single {
                    mock<K8sClientProvider>().also {
                        mockK8sClientProvider = it
                    }
                }

                // Real TemplateService — never mock configuration classes
                single { TemplateService(get(), get()) }

                // Real manifest builders
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
    fun setupMocks() {
        mockDashboardService = getKoin().get()
        mockClusterStateManager = getKoin().get()
        mockK8sService = getKoin().get()
        mockK8sClientProvider = getKoin().get()

        mockK8sClient = mock()
        val mockConfigMapOps =
            mock<MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>>>()
        val mockAnyNsOps =
            mock<AnyNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>>>()
        val mockFiltered =
            mock<FilterWatchListDeletable<ConfigMap, ConfigMapList, Resource<ConfigMap>>>()
        val emptyConfigMapList = ConfigMapList().also { it.items = mutableListOf() }

        whenever(mockK8sClient.configMaps()).thenReturn(mockConfigMapOps)
        whenever(mockConfigMapOps.inAnyNamespace()).thenReturn(mockAnyNsOps)
        whenever(mockAnyNsOps.withLabel(any<String>(), any<String>())).thenReturn(mockFiltered)
        whenever(mockFiltered.list()).thenReturn(emptyConfigMapList)
        whenever(mockK8sClientProvider.createClient(any())).thenReturn(mockK8sClient)
    }

    @Test
    fun `execute fails when no control nodes exist`() {
        val emptyState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = mutableMapOf(),
            )

        whenever(mockClusterStateManager.load()).thenReturn(emptyState)

        val command = GrafanaUpdateConfig()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No control nodes found")
    }

    @Test
    fun `execute applies all observability resources and dashboards`() {
        val stateWithControl =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControl)
        whenever(mockK8sService.applyResource(any(), any<HasMetadata>())).thenReturn(Result.success(Unit))
        whenever(mockDashboardService.uploadDashboards(any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.rolloutRestartDeployment(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.rolloutRestartDaemonSet(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.waitForPodsReady(any(), any())).thenReturn(Result.success(Unit))

        val command = GrafanaUpdateConfig()
        command.execute()

        // Verify Fabric8 resources were applied (all builders produce multiple resources)
        verify(mockK8sService, atLeastOnce()).applyResource(any(), any<HasMetadata>())
        verify(mockDashboardService).uploadDashboards(any())

        // Verify observability workloads were restarted
        verify(mockK8sService, atLeastOnce()).rolloutRestartDeployment(any(), any(), any())
        verify(mockK8sService, atLeastOnce()).rolloutRestartDaemonSet(any(), any(), any())

        // Verify success is gated on the stack reaching Ready
        verify(mockK8sService).waitForPodsReady(any(), any())
    }

    @Test
    fun `execute fails when observability stack does not become ready`() {
        val stateWithControl =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControl)
        whenever(mockK8sService.applyResource(any(), any<HasMetadata>())).thenReturn(Result.success(Unit))
        whenever(mockDashboardService.uploadDashboards(any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.rolloutRestartDeployment(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.rolloutRestartDaemonSet(any(), any(), any())).thenReturn(Result.success(Unit))
        // The stack applied and restarted, but a pod never reached Ready (e.g. Grafana CrashLoopBackOff).
        whenever(mockK8sService.waitForPodsReady(any(), any()))
            .thenReturn(
                Result.failure(
                    IllegalStateException("Pod grafana-xyz container grafana is in CrashLoopBackOff state"),
                ),
            )

        val command = GrafanaUpdateConfig()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Observability stack did not become ready")
            .hasMessageContaining("CrashLoopBackOff")
    }

    @Test
    fun `execute fails when applyResource fails`() {
        val stateWithControl =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControl)
        whenever(mockK8sService.applyResource(any(), any<HasMetadata>()))
            .thenReturn(Result.failure(RuntimeException("server side apply failed")))

        val command = GrafanaUpdateConfig()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("server side apply failed")
    }

    @Test
    fun `execute fails when uploadDashboards fails`() {
        val stateWithControl =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControl)
        whenever(mockK8sService.applyResource(any(), any<HasMetadata>())).thenReturn(Result.success(Unit))
        whenever(mockDashboardService.uploadDashboards(any()))
            .thenReturn(Result.failure(RuntimeException("Upload failed")))

        val command = GrafanaUpdateConfig()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Failed to upload dashboards")
    }
}
