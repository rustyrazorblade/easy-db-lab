package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.configuration.SshTransport

/**
 * Remote operations that reach hosts in a region named at call time.
 *
 * The shared [RemoteOperationsService] reaches hosts over the profile's [SshRoute]. A transport
 * that dials the host's address reaches any region that way, but one that goes through a regional
 * API (SSM Session Manager) reaches only instances in the region its route was built for. An AMI
 * build in another region (`--region`) therefore gets SSH connections of its own, over a route for
 * that region, which [RegionRemote.close] stops.
 *
 * @param shared the shared service
 * @param regionalRoute a new route for a region the shared route cannot reach, or null when it can
 * @param connectionsOver new SSH connections over a route
 */
class RegionalRemoteOperations(
    private val shared: RemoteOperationsService,
    private val regionalRoute: (region: String) -> SshRoute?,
    private val connectionsOver: (SshRoute) -> SSHConnectionProvider,
) {
    /** Remote operations for hosts in [region]; the caller closes it. */
    fun forRegion(region: String): RegionRemote =
        regionalRoute(region)?.let { route -> RegionRemote.Own(route, connectionsOver) } ?: RegionRemote.Shared(shared)

    companion object {
        /**
         * Remote operations for a profile on [transport] in [profileRegion]. [SshTransport.Direct]
         * reaches every region over the shared route; [SshTransport.Ssm] reaches only the profile
         * region that way, and any other region over a new route from [ssmRouteFor].
         */
        fun forProfile(
            shared: RemoteOperationsService,
            transport: SshTransport,
            profileRegion: String,
            ssmRouteFor: (region: String) -> SshRoute,
            connectionsOver: (SshRoute) -> SSHConnectionProvider,
        ): RegionalRemoteOperations =
            RegionalRemoteOperations(
                shared = shared,
                regionalRoute = { region ->
                    when {
                        transport == SshTransport.Direct || region == profileRegion -> null
                        else -> ssmRouteFor(region)
                    }
                },
                connectionsOver = connectionsOver,
            )
    }
}

/** Remote operations bound to one region, and whatever was opened to reach it. */
sealed interface RegionRemote : AutoCloseable {
    /** Runs commands on hosts in the region. */
    val operations: RemoteOperationsService

    /** The shared service, which reaches this region already; closing leaves it open. */
    class Shared(
        override val operations: RemoteOperationsService,
    ) : RegionRemote {
        override fun close() = Unit
    }

    /**
     * Connections of this region's own over [route], opened on first use. Closing stops them, which
     * closes the route; a route no connection was opened over is closed directly.
     */
    class Own(
        private val route: SshRoute,
        connectionsOver: (SshRoute) -> SSHConnectionProvider,
    ) : RegionRemote {
        private val connections = lazy { connectionsOver(route) }

        override val operations: RemoteOperationsService by lazy { DefaultRemoteOperationsService(connections.value) }

        override fun close() {
            when {
                connections.isInitialized() -> connections.value.stop()
                else -> route.close()
            }
        }
    }
}
