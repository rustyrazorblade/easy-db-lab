package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import io.github.classgraph.ClassGraph
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.text.StringSubstitutor
import java.io.File

/**
 * Service for template placeholder substitution using `__KEY__` delimiters.
 *
 * Builds context variables from cluster state and creates [Template] instances
 * that perform the actual substitution. Uses Apache Commons StringSubstitutor
 * with `__` prefix/suffix to avoid conflicts with Grafana's `${var}` syntax.
 *
 * @property clusterStateManager Provides access to current cluster state
 * @property user Provides fallback region configuration
 */
class TemplateService(
    private val clusterStateManager: ClusterStateManager,
    private val user: User,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Builds the variable map from current cluster state and user configuration.
     *
     * @return Map of variable names (without delimiters) to their runtime values
     */
    fun buildContextVariables(): Map<String, String> {
        val state = clusterStateManager.load()
        val region = state.initConfig?.region ?: user.region
        val controlHost = state.getControlHost()
        return mapOf(
            "BUCKET_NAME" to (state.s3Bucket ?: ""),
            "AWS_REGION" to region,
            "CLUSTER_NAME" to (state.initConfig?.name ?: "cluster"),
            "CONTROL_NODE_IP" to (controlHost?.privateIp ?: ""),
            "METRICS_FILTER_ID" to buildMetricsFilterId(state),
            "CLUSTER_S3_PREFIX" to buildClusterPrefix(state),
        )
    }

    private fun buildMetricsFilterId(state: ClusterState): String {
        val name = state.initConfig?.name ?: state.name
        return "edl-$name-${state.clusterId}".take(Constants.S3.MAX_METRICS_CONFIG_ID_LENGTH)
    }

    private fun buildClusterPrefix(state: ClusterState): String {
        val name = state.initConfig?.name ?: state.name
        return "${Constants.S3.CLUSTERS_PREFIX}/$name-${state.clusterId}"
    }

    /**
     * Creates a [Template] from a string.
     */
    fun fromString(template: String) = Template(template, buildContextVariables())

    /**
     * Creates a [Template] from a file.
     */
    fun fromFile(file: File) = Template(file.readText(), buildContextVariables())

    /**
     * Creates a [Template] from a classpath resource.
     *
     * @param clazz Class whose classloader is used to locate the resource
     * @param resourceName Resource path relative to [clazz]
     */
    fun fromResource(
        clazz: Class<*>,
        resourceName: String,
    ): Template {
        val text =
            clazz
                .getResourceAsStream(resourceName)
                ?.bufferedReader()
                ?.readText()
                ?: error("Resource not found: $resourceName")
        return Template(text, buildContextVariables())
    }

    /**
     * Extracts K8s YAML resources from the classpath to the given directory without substitution.
     *
     * Used by Init when cluster state is not yet available.
     *
     * @param destinationDir Directory to write extracted files to
     * @param filter Predicate on the relative path (e.g. `"core/01-namespace.yaml"`)
     * @return List of extracted files
     */
    fun extractResources(
        destinationDir: File = File(Constants.K8s.MANIFEST_DIR),
        filter: (String) -> Boolean = { true },
    ): List<File> {
        destinationDir.mkdirs()
        return scanResources(filter).map { (relativePath, content) ->
            val targetFile = File(destinationDir, relativePath)
            targetFile.parentFile?.mkdirs()
            targetFile.writeBytes(content)
            log.debug { "Extracted resource: $relativePath" }
            targetFile
        }
    }

    /**
     * Extracts K8s YAML resources from the classpath, substitutes `__KEY__` placeholders,
     * and writes the result to the given directory.
     *
     * Used by K8Apply and GrafanaDashboardService when cluster state is available.
     *
     * @param destinationDir Directory to write extracted files to
     * @param filter Predicate on the relative path (e.g. `"core/01-namespace.yaml"`)
     * @return List of extracted files
     */
    fun extractAndSubstituteResources(
        destinationDir: File = File(Constants.K8s.MANIFEST_DIR),
        filter: (String) -> Boolean = { true },
    ): List<File> {
        destinationDir.mkdirs()
        val variables = buildContextVariables()
        return scanResources(filter).map { (relativePath, content) ->
            val targetFile = File(destinationDir, relativePath)
            targetFile.parentFile?.mkdirs()
            val substituted = Template(content.toString(Charsets.UTF_8), variables).substitute()
            targetFile.writeText(substituted)
            log.debug { "Extracted and substituted: $relativePath" }
            targetFile
        }
    }

    /**
     * Scans K8s resource package for YAML files and returns their relative paths and content.
     *
     * Content is loaded as bytes inside the ScanResult.use {} block since Resource.open()
     * is only valid while the ScanResult is open.
     */
    private fun scanResources(filter: (String) -> Boolean = { true }): List<Pair<String, ByteArray>> =
        ClassGraph()
            .acceptPackages(Constants.K8s.RESOURCE_PACKAGE)
            .scan()
            .use { scanResult ->
                val yamlResources =
                    scanResult.getResourcesWithExtension("yaml") +
                        scanResult.getResourcesWithExtension("yml")
                yamlResources.mapNotNull { resource ->
                    val k8sIndex = resource.path.indexOf(Constants.K8s.PATH_PREFIX)
                    if (k8sIndex == -1) return@mapNotNull null
                    val relativePath = resource.path.substring(k8sIndex + Constants.K8s.PATH_PREFIX.length)
                    if (!filter(relativePath)) return@mapNotNull null
                    relativePath to resource.load()
                }
            }

    /**
     * A template string with context variables for `__KEY__` placeholder substitution.
     *
     * @property template The template string containing `__KEY__` placeholders
     * @property contextVariables Base variables for substitution
     */
    class Template(
        private val template: String,
        private val contextVariables: Map<String, String>,
    ) {
        constructor(file: File, contextVariables: Map<String, String>) :
            this(file.readText(), contextVariables)

        /**
         * Performs substitution, optionally merging extra variables that override context variables.
         *
         * @param extraVars Additional variables to merge (take precedence over context variables)
         * @return The template string with all placeholders replaced
         */
        fun substitute(extraVars: Map<String, String> = emptyMap()): String {
            val allVars = contextVariables + extraVars
            return StringSubstitutor(allVars, "__", "__").replace(template)
        }
    }
}
