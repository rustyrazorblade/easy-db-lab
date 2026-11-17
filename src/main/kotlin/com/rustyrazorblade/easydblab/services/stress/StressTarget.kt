package com.rustyrazorblade.easydblab.services.stress

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.CassandraPodTarget
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.access.KitEndpointServices
import com.rustyrazorblade.easydblab.services.installConfigYaml
import java.io.File

/**
 * The Cassandra a stress job drives: the contact point its driver dials, the native port, and the
 * datacenter it treats as local.
 *
 * A stress pod runs on the pod network, so the contact point is either a cluster Service DNS name
 * (the `cassandra-pods` kit) or a VM db host's private address, both reachable from a pod.
 */
data class StressTarget(
    val contactPoint: String,
    val port: Int,
    val datacenter: String,
)

/**
 * Picks the Cassandra a stress job runs against.
 *
 * The running `cassandra-pods` kit wins: its CQL endpoint's Service and Service port, and the
 * datacenter it was installed with (`--dc`). Otherwise the first VM db host on the native port, in
 * the datacenter named for the region (the VM nodes' snitch). With neither, there is nothing to
 * stress, and the error names both so the operator knows what to start.
 */
class StressTargetResolver {
    /**
     * The target for a cluster in [state] whose workspace is [workspace].
     *
     * @throws IllegalStateException when neither target exists
     */
    fun resolve(
        state: ClusterState,
        workspace: File,
    ): StressTarget =
        when {
            CassandraPodTarget.KIT in state.runningKits -> kitTarget(workspace)
            !state.hosts[ServerType.Cassandra].isNullOrEmpty() -> vmTarget(state)
            else ->
                error(
                    "Nothing to stress: the '${CassandraPodTarget.KIT}' kit is not running and the cluster has no db hosts. " +
                        "Start the '${CassandraPodTarget.KIT}' kit or provision db hosts.",
                )
        }

    private fun kitTarget(workspace: File): StressTarget {
        val kitDir = File(workspace, CassandraPodTarget.KIT)
        val configFile = File(kitDir, Constants.Kit.CONFIG_FILE)
        check(configFile.isFile) {
            "The '${CassandraPodTarget.KIT}' kit is running but ${configFile.absolutePath} is missing"
        }
        val config = installConfigYaml.decodeFromString(KitConfig.serializer(), configFile.readText())
        val endpoint =
            config.endpoints.firstOrNull { it.type == KitEndpoint.EndpointType.NATIVE }
                ?: error("The '${CassandraPodTarget.KIT}' kit declares no native CQL endpoint")
        val service =
            KitEndpointServices.read(kitDir, config).of(endpoint)
                ?: error("The '${CassandraPodTarget.KIT}' kit publishes its CQL endpoint on no Service")
        return StressTarget(
            contactPoint = "${service.name}.${service.namespace}.svc",
            port = service.port ?: endpoint.port,
            datacenter = CassandraPodTarget.inWorkspace(workspace).datacenter,
        )
    }

    private fun vmTarget(state: ClusterState): StressTarget {
        val region =
            state.infrastructure?.region
                ?: error("Infrastructure state missing region. Re-provision the cluster to fix this.")
        val host = state.hosts.getValue(ServerType.Cassandra).first()
        return StressTarget(host.privateIp, Constants.Stress.DEFAULT_CASSANDRA_PORT, region)
    }
}
