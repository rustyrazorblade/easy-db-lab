package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
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
            "BUCKET_NAME" to state.dataBucket.ifBlank { state.s3Bucket ?: "" },
            "AWS_REGION" to region,
            "CLUSTER_NAME" to (state.initConfig?.name ?: "cluster"),
            "CONTROL_NODE_IP" to (controlHost?.privateIp ?: ""),
            "METRICS_FILTER_ID" to buildMetricsFilterId(state),
            "CLUSTER_S3_PREFIX" to buildClusterPrefix(state),
            "PYROSCOPE_STORAGE_PREFIX" to buildPyroscopeStoragePrefix(state),
        )
    }

    private fun buildMetricsFilterId(state: ClusterState): String = state.metricsConfigId()

    private fun buildClusterPrefix(state: ClusterState): String = state.clusterPrefix()

    /**
     * Builds a flat storage prefix for Pyroscope (no forward slashes allowed).
     * Converts "clusters/name-id" to "pyroscope.name-id".
     */
    private fun buildPyroscopeStoragePrefix(state: ClusterState): String {
        val name = state.initConfig?.name ?: "cluster"
        val id = state.clusterId
        return "pyroscope.$name-$id"
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
