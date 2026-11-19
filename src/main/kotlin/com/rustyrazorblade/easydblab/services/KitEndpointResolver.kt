package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.access.KitEndpointServices
import com.rustyrazorblade.easydblab.services.access.ServiceAccess
import com.rustyrazorblade.easydblab.services.access.serviceTarget
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
     * endpoint. Returns an empty map when the directory or its kit.yaml is missing.
     *
     * @throws TargetKitConfigException when the kit.yaml does not parse
     * @throws TargetEndpointUnreachableException when an HTTP endpoint cannot be reached from this machine
     */
    fun resolveTargetVars(
        targetKitDir: File,
        clusterState: ClusterState,
    ): Map<String, String>
}

/**
 * The kit.yaml of the kit-ref target [target], at [file], does not parse, for [reason]. No
 * TARGET_* variable can be built from it, so the phase must not run without them.
 */
class TargetKitConfigException(
    val target: String,
    val file: File,
    val reason: String,
    cause: Throwable,
) : IllegalStateException("The kit.yaml of target kit '$target' ($file) does not parse: $reason", cause)

/**
 * The [endpoint] of the kit-ref target [target] cannot be reached from this machine, for [reason]:
 * its node is not recorded and it cannot be port-forwarded. Its TARGET_* variable would otherwise
 * name an address that fails with a DNS error far from its cause.
 */
class TargetEndpointUnreachableException(
    val target: String,
    val endpoint: String,
    val reason: String,
    cause: Throwable,
) : IllegalStateException("The '$endpoint' endpoint of target kit '$target' cannot be reached from this machine: $reason", cause)

/**
 * Resolves each endpoint to an address its reader can dial.
 *
 * A bench kit's pods run in the cluster, so an endpoint published on a Service whose port is known
 * ([KitEndpointServices]) is `<service>.<namespace>.svc` on that Service port: it needs no host in
 * `state.json` and works on any node. With no such Service, the first recorded node of its role on
 * the NodePort is used, and an endpoint with neither is skipped.
 *
 * The HTTP endpoint is the exception: bench scripts curl it from the operator's machine through the
 * workspace wrapper, where cluster DNS does not resolve, so it is opened through [access], the way
 * every CLI client reaches a Service. On a SOCKS or Direct cluster that is the node's private
 * address on the NodePort (the curl wrapper routes it through the tunnel, or this machine routes
 * it); on a port-forward cluster (`init --local`, a cluster the user brings) it is a loopback
 * port-forward to the Service, held until the CLI exits, so for the whole phase. An HTTP endpoint
 * that cannot be opened fails with [TargetEndpointUnreachableException]; it never falls back to a
 * Service DNS name.
 *
 * @param access the service-access seam the CLI reaches Services with
 */
class DefaultKitEndpointResolver(
    private val access: ServiceAccess,
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

        val kitConfig = parse(targetKitDir.name, configFile)
        val sqlCap = kitConfig.capabilities.firstOrNull { it.type == "sql" }
        val services = KitEndpointServices.read(targetKitDir, kitConfig)
        val result = mutableMapOf<String, String>()
        for (endpoint in kitConfig.endpoints) {
            val (host, port) =
                address(targetKitDir.name, endpoint, services, clusterState) ?: run {
                    log.debug { "No address for endpoint '${endpoint.name}' of '${targetKitDir.name}' — skipping" }
                    null
                } ?: continue
            result.putAll(endpoint.toTargetVars(host, sqlCap, port))
        }
        return result
    }

    private fun parse(
        target: String,
        configFile: File,
    ): KitConfig =
        runCatching { installConfigYaml.decodeFromString(KitConfig.serializer(), configFile.readText()) }
            .getOrElse { e -> throw TargetKitConfigException(target, configFile, e.message ?: e::class.simpleName.orEmpty(), e) }

    private fun address(
        target: String,
        endpoint: KitEndpoint,
        services: KitEndpointServices,
        clusterState: ClusterState,
    ): Pair<String, Int>? =
        when (endpoint.type) {
            KitEndpoint.EndpointType.HTTP -> fromThisMachine(target, endpoint, services)
            else -> inCluster(endpoint, services) ?: recordedNode(endpoint, clusterState)?.let { it to endpoint.port }
        }

    private fun inCluster(
        endpoint: KitEndpoint,
        services: KitEndpointServices,
    ): Pair<String, Int>? = services.of(endpoint)?.let { s -> s.port?.let { port -> "${s.name}.${s.namespace}.svc" to port } }

    /** The HTTP endpoint as the operator's machine dials it, through [access]. */
    private fun fromThisMachine(
        target: String,
        endpoint: KitEndpoint,
        services: KitEndpointServices,
    ): Pair<String, Int> =
        runCatching { access.open(endpoint.serviceTarget(services.of(endpoint))) }
            .map { it.host to it.port }
            .getOrElse { e ->
                if (e is InterruptedException) throw e
                throw TargetEndpointUnreachableException(target, endpoint.name, e.message ?: e::class.simpleName.orEmpty(), e)
            }

    private fun recordedNode(
        endpoint: KitEndpoint,
        clusterState: ClusterState,
    ): String? {
        val serverType = runCatching { ServerType.from(endpoint.nodeType) }.getOrNull() ?: return null
        return clusterState.hosts[serverType]?.firstOrNull()?.privateIp
    }
}
