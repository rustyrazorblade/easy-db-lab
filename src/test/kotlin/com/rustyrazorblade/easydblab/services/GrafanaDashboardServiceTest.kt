package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Test suite for GrafanaDashboardService.
 *
 * Tests dashboard extraction from JAR resources, datasource ConfigMap creation,
 * and the combined upload workflow.
 */
class GrafanaDashboardServiceTest : BaseKoinTest() {
    private lateinit var mockK8sService: K8sService
    private lateinit var mockTemplateService: TemplateService

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
                    mock<TemplateService>().also {
                        mockTemplateService = it
                    }
                }
            },
        )

    @TempDir
    lateinit var dashboardTempDir: File

    @BeforeEach
    fun setupMocks() {
        mockK8sService = getKoin().get()
        mockTemplateService = getKoin().get()
    }

    private fun createDashboardFiles(vararg names: String): List<File> =
        names.map { name ->
            File(dashboardTempDir, name).also { it.writeText("dashboard: $name") }
        }

    @Test
    fun `extractDashboardResources delegates to templateService and returns sorted files`() {
        val dashboardFiles =
            createDashboardFiles(
                "16-grafana-dashboard-s3.yaml",
                "14-grafana-dashboards-configmap.yaml",
                "15-grafana-dashboard-system.yaml",
            )
        // Return in unsorted order to verify sorting
        whenever(mockTemplateService.extractAndSubstituteResources(any(), any()))
            .thenReturn(dashboardFiles)

        val service = DefaultGrafanaDashboardService(mockK8sService, getKoin().get(), mockTemplateService)
        val files = service.extractDashboardResources()

        assertThat(files).hasSize(3)
        assertThat(files.map { it.name }).isSorted()
        verify(mockTemplateService).extractAndSubstituteResources(any(), any())
    }

    @Test
    fun `extractDashboardResources returns empty list when no dashboards found`() {
        whenever(mockTemplateService.extractAndSubstituteResources(any(), any()))
            .thenReturn(emptyList())

        val service = DefaultGrafanaDashboardService(mockK8sService, getKoin().get(), mockTemplateService)
        val files = service.extractDashboardResources()

        assertThat(files).isEmpty()
    }

    @Test
    fun `createDatasourcesConfigMap calls k8sService with correct params`() {
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))

        val service = DefaultGrafanaDashboardService(mockK8sService, getKoin().get(), mockTemplateService)
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
    fun `uploadDashboards extracts and applies all dashboards and deployment`() {
        val dashboardFiles =
            createDashboardFiles(
                "14-grafana-dashboards-configmap.yaml",
                "15-grafana-dashboard-system.yaml",
                "16-grafana-dashboard-s3.yaml",
            )
        val deploymentFile =
            File(dashboardTempDir, "41-grafana-deployment.yaml").also {
                it.writeText("deployment: grafana")
            }
        whenever(mockTemplateService.extractAndSubstituteResources(any(), any()))
            .thenReturn(dashboardFiles)
            .thenReturn(listOf(deploymentFile))
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(mockK8sService.applyManifests(any(), any()))
            .thenReturn(Result.success(Unit))

        val service = DefaultGrafanaDashboardService(mockK8sService, getKoin().get(), mockTemplateService)
        val result = service.uploadDashboards(testControlHost, "us-west-2")

        assertThat(result.isSuccess).isTrue()

        // Verify datasources ConfigMap was created
        verify(mockK8sService).createConfigMap(any(), any(), eq("grafana-datasources"), any(), any())

        // Verify applyManifests was called for each dashboard file + deployment
        verify(mockK8sService, org.mockito.kotlin.times(4)).applyManifests(any(), any())

        // Verify extraction was called twice: once for dashboards, once for deployment
        verify(mockTemplateService, org.mockito.kotlin.times(2)).extractAndSubstituteResources(any(), any())
    }

    @Test
    fun `uploadDashboards fails when createDatasourcesConfigMap fails`() {
        val dashboardFiles = createDashboardFiles("14-grafana-dashboards-configmap.yaml")
        whenever(mockTemplateService.extractAndSubstituteResources(any(), any()))
            .thenReturn(dashboardFiles)
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("ConfigMap creation failed")))

        val service = DefaultGrafanaDashboardService(mockK8sService, getKoin().get(), mockTemplateService)
        val result = service.uploadDashboards(testControlHost, "us-west-2")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Failed to create Grafana datasources ConfigMap")
    }

    @Test
    fun `uploadDashboards fails when applyManifests fails`() {
        val dashboardFiles = createDashboardFiles("14-grafana-dashboards-configmap.yaml")
        whenever(mockTemplateService.extractAndSubstituteResources(any(), any()))
            .thenReturn(dashboardFiles)
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(mockK8sService.applyManifests(any(), any()))
            .thenReturn(Result.failure(RuntimeException("Apply failed")))

        val service = DefaultGrafanaDashboardService(mockK8sService, getKoin().get(), mockTemplateService)
        val result = service.uploadDashboards(testControlHost, "us-west-2")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Failed to apply")
    }
}
