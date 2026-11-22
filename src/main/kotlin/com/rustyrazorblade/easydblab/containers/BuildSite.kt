package com.rustyrazorblade.easydblab.containers

/**
 * The cloud resources one AMI build runs on, in the build's own region: the packer network, the
 * key pair the builder launches with, and the build's instances.
 *
 * The build region (`--region`) may differ from the profile's, so every resource is looked up or
 * created there, never in the profile region. Closing it removes what was made for this build
 * alone (a temporary key pair); the packer network is kept for the next build.
 */
interface BuildSite : AutoCloseable {
    /** The region the build runs in. */
    val region: String

    /** The key pair Packer launches the builder with; its private key is the profile's. */
    val keyPairName: String

    /** The instances of this build, and only this build, in [region]. */
    val builders: BuilderInstances

    /** Removes the resources made for this build alone. */
    override fun close()
}

/** Opens the [BuildSite] for one AMI build. A port, so the build runs on any cloud adapter. */
fun interface BuildSites {
    /**
     * Ensures the packer network exists in [region] and a key pair there carries the profile's
     * key, and returns the site, bound to the build [buildId].
     */
    fun open(
        region: String,
        buildId: String,
    ): BuildSite
}
