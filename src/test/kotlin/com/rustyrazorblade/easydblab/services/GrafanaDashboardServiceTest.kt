package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Test suite for GrafanaDashboardService.
 *
 * Tests datasource ConfigMap creation and the upload workflow using
 * GrafanaManifestBuilder (mocked), the tree uploader (mocked) and K8sService (mocked).
 */
class GrafanaDashboardServiceTest : BaseKoinTest() {
    private lateinit var mockK8sService: K8sService
    private lateinit var mockManifestBuilder: GrafanaManifestBuilder
    private lateinit var mockTreeUploader: GrafanaDashboardTreeUploader

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
                    mock<K8sService>().also {
                        mockK8sService = it
                    }
                }

                single {
                    mock<GrafanaManifestBuilder>().also {
                        mockManifestBuilder = it
                    }
                }

                single {
                    mock<GrafanaDashboardTreeUploader>().also {
                        mockTreeUploader = it
                    }
                }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockK8sService = getKoin().get()
        mockManifestBuilder = getKoin().get()
        mockTreeUploader = getKoin().get()
        whenever(mockManifestBuilder.buildAllResources()).thenReturn(buildTestResources())
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.deleteConfigMapsByLabels(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.applyResource(any(), any())).thenReturn(Result.success(Unit))
    }

    private fun service() =
        DefaultGrafanaDashboardService(
            mockK8sService,
            mockManifestBuilder,
            mockTreeUploader,
            EventBus(),
            mock<OkHttpClient>(),
        )

    private fun buildTestResources(): List<HasMetadata> =
        listOf(
            ConfigMapBuilder()
                .withNewMetadata()
                .withName("grafana-dashboards-config")
                .endMetadata()
                .build(),
            DeploymentBuilder()
                .withNewMetadata()
                .withName("grafana")
                .endMetadata()
                .build(),
        )

    @Test
    fun `createDatasourcesConfigMap calls k8sService with correct params`() {
        val result = service().createDatasourcesConfigMap(testControlHost)

        assertThat(result.isSuccess).isTrue()
        verify(mockK8sService).createConfigMap(
            controlHost = eq(testControlHost),
            namespace = eq("default"),
            name = eq("grafana-datasources"),
            data = any(),
            labels = eq(mapOf("app.kubernetes.io/name" to "grafana")),
        )
    }

    @Test
    fun `uploadDashboards builds and applies all resources`() {
        val result = service().uploadDashboards(testControlHost)

        assertThat(result.isSuccess).isTrue()
        verify(mockK8sService).createConfigMap(any(), any(), eq("grafana-datasources"), any(), any())
        verify(mockK8sService, times(2)).applyResource(any(), any())
    }

    @Test
    fun `uploadDashboards puts the tree on the control node before the Grafana resources are applied`() {
        service().uploadDashboards(testControlHost)

        val order = inOrder(mockTreeUploader, mockK8sService)
        order.verify(mockTreeUploader).upload(testControlHost)
        order.verify(mockK8sService, times(2)).applyResource(any(), any())
    }

    @Test
    fun `uploadDashboards removes the per-dashboard ConfigMaps of the previous delivery before applying`() {
        // A cluster brought up by the ConfigMap-per-dashboard code still carries those objects;
        // server-side apply of the new set never deletes them. They all carried this label and
        // nothing creates it any more, so deleting every match is the cleanup.
        service().uploadDashboards(testControlHost)

        val order = inOrder(mockK8sService)
        order.verify(mockK8sService).deleteConfigMapsByLabels(
            controlHost = eq(testControlHost),
            namespace = eq("default"),
            labels = eq(mapOf("grafana_dashboard" to "1")),
        )
        order.verify(mockK8sService, times(2)).applyResource(any(), any())
    }

    @Test
    fun `uploadDashboards fails when createDatasourcesConfigMap fails`() {
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("ConfigMap creation failed")))

        val result = service().uploadDashboards(testControlHost)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Failed to create Grafana datasources ConfigMap")
    }

    @Test
    fun `uploadDashboards fails without touching K8s resources when the tree upload fails`() {
        whenever(mockTreeUploader.upload(any())).doThrow(IllegalStateException("sftp failed"))

        val result = service().uploadDashboards(testControlHost)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Failed to upload Grafana dashboards").contains("sftp failed")
        verify(mockK8sService, never()).applyResource(any(), any())
    }

    @Test
    fun `uploadDashboards fails when the legacy ConfigMap cleanup fails`() {
        whenever(mockK8sService.deleteConfigMapsByLabels(any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("forbidden")))

        val result = service().uploadDashboards(testControlHost)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Failed to delete").contains("forbidden")
        verify(mockK8sService, never()).applyResource(any(), any())
    }

    @Test
    fun `uploadDashboards fails when applyResource fails`() {
        whenever(mockK8sService.applyResource(any(), any()))
            .thenReturn(Result.failure(RuntimeException("Apply failed")))

        val result = service().uploadDashboards(testControlHost)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Failed to apply")
    }
}
