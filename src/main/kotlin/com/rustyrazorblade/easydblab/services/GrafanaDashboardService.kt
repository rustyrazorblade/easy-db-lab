package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.grafana.GrafanaDatasourceConfig
import com.rustyrazorblade.easydblab.output.OutputHandler
import io.github.classgraph.ClassGraph
import io.github.classgraph.Resource
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * Service for managing Grafana dashboard manifests.
 *
 * Handles extraction of dashboard YAML files from JAR resources (both core and ClickHouse),
 * creation of the Grafana datasources ConfigMap, and uploading dashboards to K8s.
 */
interface GrafanaDashboardService {
    /**
     * Extracts all Grafana dashboard resources from the JAR to the local k8s/ directory.
     *
     * Scans both core/ and clickhouse/ resource directories for files matching "grafana-dashboard".
     *
     * @return Sorted list of extracted dashboard files
     */
    fun extractDashboardResources(): List<File>

    /**
     * Creates the grafana-datasources ConfigMap with runtime region.
     *
     * @param controlHost The control node running K3s
     * @param region AWS region for CloudWatch datasource
     * @return Result indicating success or failure
     */
    fun createDatasourcesConfigMap(
        controlHost: ClusterHost,
        region: String,
    ): Result<Unit>

    /**
     * Extracts dashboards, creates datasources ConfigMap, and applies all to K8s.
     *
     * @param controlHost The control node running K3s
     * @param region AWS region for CloudWatch datasource
     * @return Result indicating success or failure
     */
    fun uploadDashboards(
        controlHost: ClusterHost,
        region: String,
    ): Result<Unit>
}

/**
 * Default implementation of GrafanaDashboardService.
 *
 * @property k8sService Service for K8s operations
 * @property outputHandler Handler for user-facing output
 * @property manifestTemplateService Service for replacing template placeholders in manifests
 */
class DefaultGrafanaDashboardService(
    private val k8sService: K8sService,
    private val outputHandler: OutputHandler,
    private val manifestTemplateService: ManifestTemplateService,
) : GrafanaDashboardService {
    private val log = KotlinLogging.logger {}

    companion object {
        private const val DASHBOARD_FILE_PATTERN = "grafana-dashboard"
        private const val DATASOURCES_CONFIGMAP_NAME = "grafana-datasources"
        private const val DEFAULT_NAMESPACE = "default"
    }

    override fun extractDashboardResources(): List<File> {
        val manifestDir = File(Constants.K8s.MANIFEST_DIR)
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
                        resource.path.contains(DASHBOARD_FILE_PATTERN)
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

    override fun createDatasourcesConfigMap(
        controlHost: ClusterHost,
        region: String,
    ): Result<Unit> {
        val config = GrafanaDatasourceConfig.create(region = region)
        val yamlContent = config.toYaml()

        return k8sService.createConfigMap(
            controlHost = controlHost,
            namespace = DEFAULT_NAMESPACE,
            name = DATASOURCES_CONFIGMAP_NAME,
            data = mapOf("datasources.yaml" to yamlContent),
            labels = mapOf("app.kubernetes.io/name" to "grafana"),
        )
    }

    override fun uploadDashboards(
        controlHost: ClusterHost,
        region: String,
    ): Result<Unit> {
        outputHandler.handleMessage("Extracting Grafana dashboard manifests...")
        val dashboardFiles = extractDashboardResources()

        if (dashboardFiles.isEmpty()) {
            return Result.failure(IllegalStateException("No dashboard resources found matching '$DASHBOARD_FILE_PATTERN'"))
        }

        outputHandler.handleMessage("Found ${dashboardFiles.size} dashboard file(s)")

        manifestTemplateService.replaceAll(File(Constants.K8s.MANIFEST_DIR))

        outputHandler.handleMessage("Creating Grafana datasources ConfigMap...")
        createDatasourcesConfigMap(controlHost, region).getOrElse { exception ->
            return Result.failure(
                IllegalStateException("Failed to create Grafana datasources ConfigMap: ${exception.message}", exception),
            )
        }

        dashboardFiles.forEach { file ->
            outputHandler.handleMessage("Applying ${file.name}...")
            k8sService
                .applyManifests(controlHost, file.toPath())
                .getOrElse { exception ->
                    return Result.failure(
                        IllegalStateException("Failed to apply ${file.name}: ${exception.message}", exception),
                    )
                }
        }

        outputHandler.handleMessage("All Grafana dashboards applied successfully!")
        return Result.success(Unit)
    }

    private fun extractK8sResource(resource: Resource): File? {
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
