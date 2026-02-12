package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Test suite for GrafanaDashboardService.
 *
 * Tests dashboard extraction from JAR resources, datasource ConfigMap creation,
 * and the combined upload workflow.
 */
class GrafanaDashboardServiceTest : BaseKoinTest() {
    private lateinit var mockK8sService: K8sService
    private lateinit var mockManifestTemplateService: ManifestTemplateService

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
                    mock<ManifestTemplateService>().also {
                        mockManifestTemplateService = it
                    }
                }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockK8sService = getKoin().get()
        mockManifestTemplateService = getKoin().get()
    }

    @Test
    fun `extractDashboardResources finds all 7 dashboard files from core and clickhouse`() {
        val service = DefaultGrafanaDashboardService(mockK8sService, getKoin().get(), mockManifestTemplateService)
        val files = service.extractDashboardResources()

        assertThat(files).hasSize(7)

        // Verify all expected dashboard files are present
        val fileNames = files.map { it.name }
        assertThat(fileNames).contains("14-grafana-dashboards-configmap.yaml")
        assertThat(fileNames).contains("15-grafana-dashboard-system.yaml")
        assertThat(fileNames).contains("16-grafana-dashboard-s3.yaml")
        assertThat(fileNames).contains("17-grafana-dashboard-emr.yaml")
        assertThat(fileNames).contains("18-grafana-dashboard-opensearch.yaml")
        assertThat(fileNames).contains("14-grafana-dashboard-clickhouse.yaml")
        assertThat(fileNames).contains("17-grafana-dashboard-clickhouse-logs.yaml")
    }

    @Test
    fun `extractDashboardResources excludes non-dashboard files`() {
        val service = DefaultGrafanaDashboardService(mockK8sService, getKoin().get(), mockManifestTemplateService)
        val files = service.extractDashboardResources()

        assertThat(files).allSatisfy { file ->
            assertThat(file.name).contains("grafana-dashboard")
        }
    }

    @Test
    fun `extractDashboardResources returns files sorted by name`() {
        val service = DefaultGrafanaDashboardService(mockK8sService, getKoin().get(), mockManifestTemplateService)
        val files = service.extractDashboardResources()

        val names = files.map { it.name }
        assertThat(names).isSorted()
    }

    @Test
    fun `createDatasourcesConfigMap calls k8sService with correct params`() {
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))

        val service = DefaultGrafanaDashboardService(mockK8sService, getKoin().get(), mockManifestTemplateService)
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
    fun `uploadDashboards extracts and applies all dashboards`() {
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(mockK8sService.applyManifests(any(), any()))
            .thenReturn(Result.success(Unit))

        val service = DefaultGrafanaDashboardService(mockK8sService, getKoin().get(), mockManifestTemplateService)
        val result = service.uploadDashboards(testControlHost, "us-west-2")

        assertThat(result.isSuccess).isTrue()

        // Verify datasources ConfigMap was created
        verify(mockK8sService).createConfigMap(any(), any(), eq("grafana-datasources"), any(), any())

        // Verify applyManifests was called for each of the 7 dashboard files
        verify(mockK8sService, org.mockito.kotlin.times(7)).applyManifests(any(), any())

        // Verify template substitution was called
        verify(mockManifestTemplateService).replaceAll(any())
    }

    @Test
    fun `uploadDashboards fails when createDatasourcesConfigMap fails`() {
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("ConfigMap creation failed")))

        val service = DefaultGrafanaDashboardService(mockK8sService, getKoin().get(), mockManifestTemplateService)
        val result = service.uploadDashboards(testControlHost, "us-west-2")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Failed to create Grafana datasources ConfigMap")
    }

    @Test
    fun `uploadDashboards fails when applyManifests fails`() {
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(mockK8sService.applyManifests(any(), any()))
            .thenReturn(Result.failure(RuntimeException("Apply failed")))

        val service = DefaultGrafanaDashboardService(mockK8sService, getKoin().get(), mockManifestTemplateService)
        val result = service.uploadDashboards(testControlHost, "us-west-2")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Failed to apply")
    }
}
