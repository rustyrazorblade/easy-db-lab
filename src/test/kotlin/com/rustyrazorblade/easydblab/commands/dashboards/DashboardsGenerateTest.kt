package com.rustyrazorblade.easydblab.commands.dashboards

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Test suite for DashboardsGenerate command.
 */
class DashboardsGenerateTest : BaseKoinTest() {
    private lateinit var mockDashboardService: GrafanaDashboardService

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<GrafanaDashboardService>().also {
                        mockDashboardService = it
                    }
                }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockDashboardService = getKoin().get()
    }

    @Test
    fun `execute extracts dashboard files via service`() {
        val testFiles =
            listOf(
                File("k8s/core/15-grafana-dashboard-system.yaml"),
                File("k8s/core/16-grafana-dashboard-s3.yaml"),
            )
        whenever(mockDashboardService.extractDashboardResources()).thenReturn(testFiles)

        val command = DashboardsGenerate()
        command.execute()

        verify(mockDashboardService).extractDashboardResources()
    }

    @Test
    fun `execute fails when no dashboard resources found`() {
        whenever(mockDashboardService.extractDashboardResources()).thenReturn(emptyList())

        val command = DashboardsGenerate()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No dashboard resources found")
    }
}
