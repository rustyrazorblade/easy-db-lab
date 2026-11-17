package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.access.KitEndpointServices
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
     * endpoint. Returns an empty map if the directory or kit.yaml is missing or unparseable.
     */
    fun resolveTargetVars(
        targetKitDir: File,
        clusterState: ClusterState,
    ): Map<String, String>
}

/**
 * Resolves each endpoint to an address its reader can dial.
 *
 * A bench kit's pods run in the cluster, so an endpoint published on a Service whose port is known
 * ([KitEndpointServices]) is `<service>.<namespace>.svc` on that Service port: it needs no host in
 * `state.json` and works on any node. The HTTP endpoint is the exception: bench scripts curl it
 * from the operator's machine through the workspace wrapper, where cluster DNS does not resolve, so
 * it keeps the first recorded node of its role on the NodePort, and uses the Service only when no
 * node is recorded. An endpoint with neither is skipped.
 */
class DefaultKitEndpointResolver : KitEndpointResolver {
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
        val services = KitEndpointServices.read(targetKitDir, kitConfig)
        val result = mutableMapOf<String, String>()
        for (endpoint in kitConfig.endpoints) {
            val (host, port) =
                address(endpoint, services, clusterState) ?: run {
                    log.debug { "No address for endpoint '${endpoint.name}' of '${targetKitDir.name}' — skipping" }
                    null
                } ?: continue
            result.putAll(endpoint.toTargetVars(host, sqlCap, port))
        }
        return result
    }

    private fun address(
        endpoint: KitEndpoint,
        services: KitEndpointServices,
        clusterState: ClusterState,
    ): Pair<String, Int>? {
        val service =
            services
                .of(endpoint)
                ?.let { s -> s.port?.let { port -> "${s.name}.${s.namespace}.svc" to port } }
        val node = recordedNode(endpoint, clusterState)?.let { it to endpoint.port }
        return when (endpoint.type) {
            KitEndpoint.EndpointType.HTTP -> node ?: service
            else -> service ?: node
        }
    }

    private fun recordedNode(
        endpoint: KitEndpoint,
        clusterState: ClusterState,
    ): String? {
        val serverType = runCatching { ServerType.from(endpoint.nodeType) }.getOrNull() ?: return null
        return clusterState.hosts[serverType]?.firstOrNull()?.privateIp
    }
}
