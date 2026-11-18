package com.rustyrazorblade.easydblab.configuration

/**
 * The AWS profile's region, read only when it is asked for. It is the region of an EC2 cluster whose
 * state records no init configuration (an old state, or the bootstrap state a restore builds before
 * `state.json` is back), which is where that cluster's bucket and nodes are.
 *
 * Asking is what loads the profile, so a caller holds this instead of a [User]. Only a cluster with an AWS
 * account ([NodeCapability.AWS_ACCOUNT]) ever asks: a local or brought workspace has no profile, so its
 * region is the one it recorded (`""`), or `""` when it records none.
 */
fun interface ProfileRegion {
    /** The profile's region; loads the profile. */
    fun region(): String

    companion object {
        /** For a caller with no profile to fall back on: a state with no region is then an error. */
        val NONE =
            ProfileRegion {
                error(
                    "The cluster state records no region and no AWS profile region is available to fall back on. " +
                        "Run 'easy-db-lab profile setup', or 'easy-db-lab init' again to record one.",
                )
            }
    }
}

/**
 * The region `init` recorded. A state that records none takes the profile's region only when its
 * environment has an AWS account; any other has no region (`""`) and never loads the profile.
 */
fun ClusterState.region(profileRegion: ProfileRegion): String =
    initConfig?.region
        ?: when (NodeCapability.AWS_ACCOUNT in NodeCapabilities.from(this)) {
            true -> profileRegion.region()
            false -> ""
        }
