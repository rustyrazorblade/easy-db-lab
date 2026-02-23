package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Test suite for GrafanaDashboardService.
 *
 * Tests datasource ConfigMap creation and the upload workflow using
 * GrafanaManifestBuilder (mocked) and K8sService (mocked).
 */
class GrafanaDashboardServiceTest : BaseKoinTest() {
    private lateinit var mockK8sService: K8sService
    private lateinit var mockManifestBuilder: GrafanaManifestBuilder

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
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockK8sService = getKoin().get()
        mockManifestBuilder = getKoin().get()
    }

    private fun buildTestResources(): List<HasMetadata> =
        listOf(
            ConfigMapBuilder()
                .withNewMetadata()
                .withName("grafana-dashboards-config")
                .endMetadata()
                .build(),
            ConfigMapBuilder()
                .withNewMetadata()
                .withName("grafana-dashboard-system")
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
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))

        val service =
            DefaultGrafanaDashboardService(
                mockK8sService,
                mockManifestBuilder,
                com.rustyrazorblade.easydblab.events
                    .EventBus(),
            )
        val result = service.createDatasourcesConfigMap(testControlHost, "us-east-1")

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
        val resources = buildTestResources()
        whenever(mockManifestBuilder.buildAllResources()).thenReturn(resources)
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(mockK8sService.applyResource(any(), any()))
            .thenReturn(Result.success(Unit))

        val service =
            DefaultGrafanaDashboardService(
                mockK8sService,
                mockManifestBuilder,
                com.rustyrazorblade.easydblab.events
                    .EventBus(),
            )
        val result = service.uploadDashboards(testControlHost, "us-west-2")

        assertThat(result.isSuccess).isTrue()

        // Verify datasources ConfigMap was created
        verify(mockK8sService).createConfigMap(any(), any(), eq("grafana-datasources"), any(), any())

        // Verify applyResource was called for each resource
        verify(mockK8sService, times(3)).applyResource(any(), any())
    }

    @Test
    fun `uploadDashboards fails when createDatasourcesConfigMap fails`() {
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("ConfigMap creation failed")))

        val service =
            DefaultGrafanaDashboardService(
                mockK8sService,
                mockManifestBuilder,
                com.rustyrazorblade.easydblab.events
                    .EventBus(),
            )
        val result = service.uploadDashboards(testControlHost, "us-west-2")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Failed to create Grafana datasources ConfigMap")
    }

    @Test
    fun `uploadDashboards fails when applyResource fails`() {
        val resources = buildTestResources()
        whenever(mockManifestBuilder.buildAllResources()).thenReturn(resources)
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(mockK8sService.applyResource(any(), any()))
            .thenReturn(Result.failure(RuntimeException("Apply failed")))

        val service =
            DefaultGrafanaDashboardService(
                mockK8sService,
                mockManifestBuilder,
                com.rustyrazorblade.easydblab.events
                    .EventBus(),
            )
        val result = service.uploadDashboards(testControlHost, "us-west-2")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Failed to apply")
    }
}
