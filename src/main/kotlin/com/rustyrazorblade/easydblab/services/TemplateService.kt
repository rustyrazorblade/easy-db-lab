package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.configuration.ProfileRegion
import com.rustyrazorblade.easydblab.configuration.region
import com.rustyrazorblade.easydblab.configuration.store.S3Backend
import com.rustyrazorblade.easydblab.configuration.store.StoreBackend
import com.rustyrazorblade.easydblab.configuration.store.StoreBlockRenderer
import org.apache.commons.text.StringSubstitutor
import java.io.File

private val UNRESOLVED_VAR_PATTERN = Regex("""__([A-Z][A-Z0-9_]*)__""")

/**
 * Service for template placeholder substitution using `__KEY__` delimiters.
 *
 * Builds context variables from cluster state and creates [Template] instances
 * that perform the actual substitution. Uses Apache Commons StringSubstitutor
 * with `__` prefix/suffix to avoid conflicts with Grafana's `${var}` syntax.
 *
 * The region is the one the cluster state records. Only an EC2 state with no init configuration
 * falls back to the AWS profile's ([profileRegion]); a local workspace records one, so rendering a
 * template there never loads a profile.
 *
 * @property clusterStateManager Provides access to current cluster state
 * @property profileRegion the region of a state that records none; loads the profile when asked
 */
class TemplateService(
    private val clusterStateManager: ClusterStateManager,
    private val profileRegion: ProfileRegion = ProfileRegion.NONE,
) {
    /**
     * Builds the variable map from current cluster state.
     *
     * @return Map of variable names (without delimiters) to their runtime values
     */
    fun buildContextVariables(): Map<String, String> {
        val state = clusterStateManager.load()
        val region = state.region(profileRegion)
        val controlHost = state.getControlHost()
        return mapOf(
            "AWS_REGION" to region,
            "CLUSTER_ID" to state.clusterId,
            "CLUSTER_NAME" to state.clusterLabelName(),
            "CONTROL_NODE_IP" to (controlHost?.privateIp.orEmpty()),
            "METRICS_FILTER_ID" to buildMetricsFilterId(state),
            "CLUSTER_S3_PREFIX" to buildClusterPrefix(state),
            "TENANT" to state.tenant(),
        ) + StoreBackend.ofOrNull(state, region)?.let { storeVariables(it, state) }.orEmpty()
    }

    /**
     * The account bucket and the store fragment variables. Only Pyroscope's S3 fragment and the
     * AWS-only kits and builders read them, so a cluster with no account bucket (a local or brought
     * one, #1061) has none, and a template that needs one is left with its placeholder.
     */
    private fun storeVariables(
        backend: StoreBackend,
        state: ClusterState,
    ): Map<String, String> {
        val store = ObservabilityStore(backend.bucket, state.tenant(), backend.prefix)
        return mapOf(
            "ACCOUNT_BUCKET" to store.bucket,
            "STORE_BUCKET" to backend.bucket,
            "PROFILES_STORE_PREFIX" to store.profilesPrefix(),
        ) + storeLocation(backend)
    }

    /**
     * The store the cluster writes to, in the region the cluster recorded.
     * The backend builders pick each config's object-store block from it ([StoreBlockRenderer.of]).
     */
    fun storeBackend(): StoreBackend {
        val state = clusterStateManager.load()
        return StoreBackend.from(state, state.region(profileRegion))
    }

    /** Where the store is reached, for the build-time store fragments (Pyroscope's): only its S3 fragment reads these. */
    private fun storeLocation(backend: StoreBackend): Map<String, String> =
        when (backend) {
            is S3Backend -> mapOf("STORE_REGION" to backend.region, "STORE_ENDPOINT" to backend.endpoint)
        }

    private fun buildMetricsFilterId(state: ClusterState): String = state.metricsConfigId()

    private fun buildClusterPrefix(state: ClusterState): String = state.clusterPrefix()

    fun renderKitTemplate(
        templateContent: String,
        vars: TemplateVariables,
        extraVars: Map<String, String> = emptyMap(),
        onUnresolved: (List<String>) -> Unit = {},
    ): String {
        val allVars = vars.toMap() + extraVars
        val rendered = StringSubstitutor(allVars, "__", "__").replace(templateContent)
        val unresolved = UNRESOLVED_VAR_PATTERN.findAll(rendered).map { it.groupValues[1] }.toList()
        if (unresolved.isNotEmpty()) onUnresolved(unresolved)
        return rendered
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
