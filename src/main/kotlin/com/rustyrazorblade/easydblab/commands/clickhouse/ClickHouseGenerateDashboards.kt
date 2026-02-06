package com.rustyrazorblade.easydblab.commands.clickhouse

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.K8sService
import io.github.classgraph.ClassGraph
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command
import java.io.File

/**
 * Re-extract and apply ClickHouse Grafana dashboard manifests.
 *
 * This command extracts dashboard YAML files from JAR resources to the local
 * k8s/clickhouse/ directory, then applies them to the K8s cluster. This enables
 * rapid dashboard iteration without re-running init.
 *
 * The ConfigMaps are already volume-mounted in the Grafana deployment with
 * optional: true, so updating them triggers Grafana's provisioner to reload.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "generate-dashboards",
    description = ["Re-extract and apply ClickHouse Grafana dashboard manifests"],
)
class ClickHouseGenerateDashboards : PicoBaseCommand() {
    private val log = KotlinLogging.logger {}
    private val k8sService: K8sService by inject()

    companion object {
        private const val K8S_MANIFEST_BASE = "k8s/clickhouse"
        private const val DASHBOARD_FILE_PATTERN = "grafana-dashboard"
    }

    override fun execute() {
        val controlNode = getControlNode()

        outputHandler.handleMessage("Extracting ClickHouse Grafana dashboard manifests...")
        val dashboardFiles = extractDashboardResources()

        if (dashboardFiles.isEmpty()) {
            error("No dashboard resources found matching '$DASHBOARD_FILE_PATTERN'")
        }

        outputHandler.handleMessage("Found ${dashboardFiles.size} dashboard file(s)")

        dashboardFiles.forEach { file ->
            outputHandler.handleMessage("Applying ${file.name}...")
            k8sService
                .applyManifests(controlNode, file.toPath())
                .getOrElse { exception ->
                    error("Failed to apply ${file.name}: ${exception.message}")
                }
        }

        outputHandler.handleMessage("ClickHouse Grafana dashboards applied successfully!")
    }

    private fun getControlNode(): ClusterHost {
        val controlHosts = clusterState.hosts[ServerType.Control]
        if (controlHosts.isNullOrEmpty()) {
            error("No control nodes found. Please ensure the environment is running.")
        }
        return controlHosts.first()
    }

    internal fun extractDashboardResources(): List<File> {
        val manifestDir = File(K8S_MANIFEST_BASE)
        if (!manifestDir.exists()) {
            manifestDir.mkdirs()
        }

        val extractedFiles = mutableListOf<File>()

        ClassGraph()
            .acceptPackages(Constants.K8s.RESOURCE_PACKAGE)
            .scan()
            .use { scanResult ->
                val yamlResources =
                    scanResult.getResourcesWithExtension("yaml") +
                        scanResult.getResourcesWithExtension("yml")

                val dashboardResources =
                    yamlResources.filter { resource ->
                        val resourcePath = resource.path
                        resourcePath.contains("clickhouse/") && resourcePath.contains(DASHBOARD_FILE_PATTERN)
                    }

                dashboardResources.forEach { resource ->
                    val file = extractK8sResource(resource)
                    if (file != null) {
                        extractedFiles.add(file)
                    }
                }
            }

        return extractedFiles.sortedBy { it.name }
    }

    private fun extractK8sResource(resource: io.github.classgraph.Resource): File? {
        val resourcePath = resource.path
        val k8sIndex = resourcePath.indexOf(Constants.K8s.PATH_PREFIX)
        if (k8sIndex == -1) return null

        val relativePath = resourcePath.substring(k8sIndex + Constants.K8s.PATH_PREFIX.length)
        val targetFile = File(Constants.K8s.MANIFEST_DIR, relativePath)

        targetFile.parentFile?.mkdirs()
        resource.open().use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        log.debug { "Extracted dashboard resource: $relativePath" }
        return targetFile
    }
}
