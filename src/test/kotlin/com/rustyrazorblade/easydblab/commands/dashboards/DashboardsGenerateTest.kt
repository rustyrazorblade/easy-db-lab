package com.rustyrazorblade.easydblab.commands.dashboards

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboard
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Test suite for DashboardsGenerate command.
 */
class DashboardsGenerateTest : BaseKoinTest() {
    private lateinit var mockManifestBuilder: GrafanaManifestBuilder

    @TempDir
    lateinit var outputTempDir: File

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<GrafanaManifestBuilder>().also {
                        mockManifestBuilder = it
                    }
                }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockManifestBuilder = getKoin().get()
    }

    @Test
    fun `execute generates YAML files from built resources`() {
        val resources: List<HasMetadata> =
            listOf(
                ConfigMapBuilder()
                    .withNewMetadata()
                    .withName("grafana-dashboards-config")
                    .endMetadata()
                    .build(),
                ConfigMapBuilder()
                    .withNewMetadata()
                    .withName(GrafanaDashboard.SYSTEM.configMapName)
                    .endMetadata()
                    .build(),
                DeploymentBuilder()
                    .withNewMetadata()
                    .withName("grafana")
                    .endMetadata()
                    .build(),
            )
        whenever(mockManifestBuilder.buildAllResources()).thenReturn(resources)

        val command = DashboardsGenerate()
        command.outputDir = outputTempDir.absolutePath
        command.execute()

        val generatedFiles = outputTempDir.listFiles()?.toList() ?: emptyList()
        assertThat(generatedFiles).hasSize(3)
        assertThat(generatedFiles.map { it.name }).containsExactlyInAnyOrder(
            "configmap-grafana-dashboards-config.yaml",
            "configmap-${GrafanaDashboard.SYSTEM.configMapName}.yaml",
            "deployment-grafana.yaml",
        )
    }
}
