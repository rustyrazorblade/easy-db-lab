package com.rustyrazorblade.easydblab.configuration

/**
 * The AWS profile's region, read only when it is asked for. It is the region of an EC2 cluster whose
 * state records no init configuration (an old state, or the bootstrap state a restore builds before
 * `state.json` is back), which is where that cluster's bucket and nodes are.
 *
 * Asking is what loads the profile, so a caller holds this instead of a [User]: a local workspace
 * records a region (`""`) and never asks.
 */
fun interface ProfileRegion {
    /** The profile's region; loads the profile. */
    fun region(): String

    companion object {
        /** For a caller with no profile to fall back on: a state with no region is then an error. */
        val NONE =
            ProfileRegion {
                error("The cluster state records no region and no AWS profile region is available to fall back on")
            }
    }
}

/** The region `init` recorded, or the profile's for a state that records none. */
fun ClusterState.region(profileRegion: ProfileRegion): String = initConfig?.region ?: profileRegion.region()
