package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
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
        val result = mutableMapOf<String, String>()

        for (endpoint in kitConfig.endpoints) {
            val serverType =
                try {
                    ServerType.from(endpoint.nodeType)
                } catch (e: Exception) {
                    log.debug {
                        "Unknown node-type '${endpoint.nodeType}' in '${targetKitDir.name}' endpoint '${endpoint.name}' — skipping"
                    }
                    continue
                }
            val ip = clusterState.hosts[serverType]?.firstOrNull()?.privateIp
            if (ip == null) {
                log.debug { "No nodes of type '${endpoint.nodeType}' in cluster state — skipping endpoint '${endpoint.name}'" }
                continue
            }

            result.putAll(endpoint.toTargetVars(ip, sqlCap))
        }

        return result
    }
}
