package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.text.StringSubstitutor
import java.io.File

/**
 * Service for replacing template placeholders in K8s manifest files with runtime values.
 *
 * Uses Apache Commons StringSubstitutor with `__` prefix and `__` suffix delimiters
 * (e.g., `__BUCKET_NAME__`) to avoid conflicts with Grafana's `${var}` syntax.
 */
interface ManifestTemplateService {
    /**
     * Builds the variable map from current cluster state and user configuration.
     *
     * @return Map of variable names (without delimiters) to their runtime values
     */
    fun buildVariables(): Map<String, String>

    /**
     * Replaces all template placeholders in YAML files within the given directory.
     *
     * Walks all `.yaml` and `.yml` files and applies substitution using the
     * variable map built from cluster state.
     *
     * @param manifestDir Directory containing manifest files to process
     */
    fun replaceAll(manifestDir: File)
}

/**
 * Default implementation of ManifestTemplateService.
 *
 * @property clusterStateManager Provides access to current cluster state
 * @property user Provides fallback region configuration
 */
class DefaultManifestTemplateService(
    private val clusterStateManager: ClusterStateManager,
    private val user: User,
) : ManifestTemplateService {
    private val log = KotlinLogging.logger {}

    override fun buildVariables(): Map<String, String> {
        val state = clusterStateManager.load()
        val region = state.initConfig?.region ?: user.region
        val controlHost = state.getControlHost()
        return mapOf(
            "BUCKET_NAME" to (state.s3Bucket ?: ""),
            "AWS_REGION" to region,
            "CLUSTER_NAME" to (state.initConfig?.name ?: "cluster"),
            "CONTROL_NODE_IP" to (controlHost?.privateIp ?: ""),
        )
    }

    override fun replaceAll(manifestDir: File) {
        val variables = buildVariables()
        val substitutor = StringSubstitutor(variables, "__", "__")

        manifestDir
            .walkTopDown()
            .filter { it.isFile && (it.extension == "yaml" || it.extension == "yml") }
            .forEach { file ->
                val original = file.readText()
                val replaced = substitutor.replace(original)
                if (replaced != original) {
                    file.writeText(replaced)
                    log.debug { "Replaced template placeholders in ${file.name}" }
                }
            }
    }
}
