package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
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
     * directory or kit.yaml is missing or unparseable. A NodePort endpoint whose node type has
     * no node is left out. An endpoint served by one pod that no pod serves is handled by
     * [unserved]: [UnservedEndpoints.FAIL] throws [TargetEndpointNotServedException], because the
     * target kit is not running and the bench kit must not start against a node that does not
     * serve it; [UnservedEndpoints.OMIT] leaves its vars out.
     */
    fun resolveTargetVars(
        targetKitDir: File,
        clusterState: ClusterState,
        unserved: UnservedEndpoints = UnservedEndpoints.FAIL,
    ): Map<String, String>
}

/**
 * What [KitEndpointResolver.resolveTargetVars] does with a target endpoint, served by one pod,
 * that no pod serves. A phase that works against the target fails; a teardown phase (`stop`,
 * `uninstall`) removes what the bench kit made, and runs after its target is stopped.
 */
enum class UnservedEndpoints {
    /** Fail the phase before its steps run. */
    FAIL,

    /** Leave the endpoint's vars out and run the phase. */
    OMIT,
}

/**
 * Reads the target kit's `kit.yaml` and turns each endpoint into TARGET_* vars at the address
 * [endpointHost] resolves: the first node of the endpoint's node type, or for an endpoint served by
 * one pod the node that runs that pod, so a bench kit never targets a node that does not serve.
 */
class DefaultKitEndpointResolver(
    private val endpointHost: KitEndpointHost,
) : KitEndpointResolver {
    private val log = KotlinLogging.logger {}

    override fun resolveTargetVars(
        targetKitDir: File,
        clusterState: ClusterState,
        unserved: UnservedEndpoints,
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
        val lookup = endpointHost.once()
        return kitConfig.endpoints
            .flatMap { endpoint ->
                when (val found = lookup.lookup(endpoint, targetKitDir.name, kitConfig.namespace, clusterState.hosts)) {
                    is KitEndpointHost.Lookup.Served -> varsAt(found.addresses.firstOrNull(), endpoint, sqlCap, targetKitDir.name)
                    is KitEndpointHost.Lookup.Unserved ->
                        when (unserved) {
                            UnservedEndpoints.FAIL ->
                                throw TargetEndpointNotServedException(targetKitDir.name, endpoint.name, found.reason)
                            UnservedEndpoints.OMIT -> {
                                log.debug { "No TARGET_* vars for '${targetKitDir.name}' endpoint '${endpoint.name}': ${found.reason}" }
                                emptyList()
                            }
                        }
                }
            }.toMap()
    }

    /**
     * The TARGET_* vars of a NodePort [endpoint] at [ip], or none when its node type has no node:
     * routine, because a kit declares endpoints on several node types.
     */
    private fun varsAt(
        ip: String?,
        endpoint: KitEndpoint,
        sqlCap: KitCapability?,
        kitName: String,
    ): List<Pair<String, String>> {
        if (ip == null) {
            log.debug { "No TARGET_* vars for '$kitName' endpoint '${endpoint.name}': no ${endpoint.nodeType} nodes" }
            return emptyList()
        }
        return endpoint.toTargetVars(ip, sqlCap).toList()
    }
}

/**
 * The target kit [targetKit] declares endpoint [endpointName], served by one pod, and no pod serves
 * it now, for [reason]. The kit phase that asked for its TARGET_* vars fails before its steps run.
 */
class TargetEndpointNotServedException(
    val targetKit: String,
    val endpointName: String,
    val reason: String,
) : IllegalStateException(
        "Target kit '$targetKit' does not serve endpoint '$endpointName': $reason. " +
            "Start it with '$targetKit start' and check it with '$targetKit status'.",
    )
