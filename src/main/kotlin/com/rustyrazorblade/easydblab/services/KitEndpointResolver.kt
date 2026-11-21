package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.reason
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * Resolves the SQL endpoint details of a target kit into TARGET_* environment variables.
 *
 * When a bench kit starts with a kit-ref arg (e.g. --target clickhouse), this service
 * reads the target kit's kit.yaml from its workspace directory and produces env vars
 * such as TARGET_JDBC_URL, TARGET_PG_HOST, and TARGET_MYSQL_HOST. The bench kit's
 * start script uses these vars without needing to know which database it is targeting.
 */
interface KitEndpointResolver {
    /**
     * Reads [targetKitDir]'s kit.yaml and returns TARGET_* env vars for each declared
     * endpoint, resolving host IPs from [clusterState]. Returns an empty map if the
     * directory or kit.yaml is missing or unparseable.
     */
    fun resolveTargetVars(
        targetKitDir: File,
        clusterState: ClusterState,
    ): Map<String, String>
}

/**
 * Reads the target kit's `kit.yaml` and turns each endpoint into TARGET_* vars at the address
 * [endpointHost] resolves: the first node of the endpoint's node type, or for an endpoint with a
 * `pod-selector` the node that runs its pod, so a bench kit never targets a node that does not serve.
 */
class DefaultKitEndpointResolver(
    private val endpointHost: KitEndpointHost,
) : KitEndpointResolver {
    private val log = KotlinLogging.logger {}

    override fun resolveTargetVars(
        targetKitDir: File,
        clusterState: ClusterState,
    ): Map<String, String> {
        val configFile = File(targetKitDir, Constants.Kit.CONFIG_FILE)
        if (!configFile.isFile) {
            log.debug { "Target kit directory '${targetKitDir.name}' has no kit.yaml — no TARGET_* vars injected" }
            return emptyMap()
        }

        val kitConfig =
            try {
                installConfigYaml.decodeFromString(KitConfig.serializer(), configFile.readText())
            } catch (e: Exception) {
                log.warn(e) { "Failed to parse kit.yaml for '${targetKitDir.name}' — no TARGET_* vars injected" }
                return emptyMap()
            }

        val sqlCap = kitConfig.capabilities.firstOrNull { it.type == "sql" }
        val result = mutableMapOf<String, String>()

        val namespace = KitEndpointAddresses.namespaceOf(kitConfig)
        val lookup = endpointHost.once()
        for (endpoint in kitConfig.endpoints) {
            lookup
                .resolve(endpoint, targetKitDir.name, namespace, clusterState.hosts)
                .onSuccess { ip -> result.putAll(endpoint.toTargetVars(ip, sqlCap)) }
                .onFailure { e -> logSkipped(targetKitDir.name, endpoint, e) }
        }

        return result
    }

    /**
     * A NodePort endpoint with no node of its type is routine (a kit declares endpoints on several
     * node types), so it is a debug line; a `pod-selector` endpoint with no serving pod means the
     * target kit is not running, which the user needs to see.
     */
    private fun logSkipped(
        kitName: String,
        endpoint: KitEndpoint,
        e: Throwable,
    ) {
        val message = "No TARGET_* vars for '$kitName' endpoint '${endpoint.name}': ${e.reason()}"
        when (endpoint.podSelector) {
            "" -> log.debug { message }
            else -> log.warn { message }
        }
    }
}
