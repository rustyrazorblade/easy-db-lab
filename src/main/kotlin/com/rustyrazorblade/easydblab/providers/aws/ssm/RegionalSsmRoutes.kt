package com.rustyrazorblade.easydblab.providers.aws.ssm

/**
 * Builds the `ssm` route for a region named at call time. A Session Manager session reaches only
 * instances in the region it is started in, so an AMI build outside the profile region reaches
 * its builder over a route of its own. Each call builds a new route; the caller closes it.
 */
fun interface RegionalSsmRoutes {
    /** A new route to port [sshPort] of instances in [region]. */
    fun forRegion(
        region: String,
        sshPort: Int,
    ): SsmSshRoute
}
