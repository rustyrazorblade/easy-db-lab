package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredResources
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService

/**
 * Asks AWS whether the workspace's cluster still has infrastructure.
 *
 * `init --clean` deletes `state.json`, the only local record of the cluster's VPC, so a check of
 * local files cannot tell whether that deletion would orphan running resources. This looks the
 * cluster up the way `down` does: from the VPC ID in the cluster state, through the same
 * discovery `down --dry-run` uses (the whole VPC, or only the cluster's `ClusterId`-tagged
 * resources when the VPC was supplied with `init --vpc`).
 */
class LiveInfrastructureLookup(
    private val clusterStateManager: ClusterStateManager,
    private val infrastructureService: AwsInfrastructureService,
) {
    /**
     * The cluster's live resources, or null when the workspace has no cluster state, the state
     * records no VPC, or AWS shows nothing left. An AWS failure propagates: a cluster that cannot
     * be checked is not treated as gone.
     */
    fun find(): DiscoveredResources? {
        if (!clusterStateManager.exists()) return null
        val state = clusterStateManager.load()
        val vpcId = state.vpcId ?: return null
        val suppliedVpcCluster = state.clusterId.takeIf { state.vpcSupplied }
        val result =
            when (suppliedVpcCluster) {
                null -> infrastructureService.teardownVpc(vpcId, dryRun = true)
                else -> infrastructureService.teardownClusterInSuppliedVpc(vpcId, suppliedVpcCluster, dryRun = true)
            }
        return result.resourcesDeleted.firstOrNull()?.takeIf { it.hasResources() }
    }
}
