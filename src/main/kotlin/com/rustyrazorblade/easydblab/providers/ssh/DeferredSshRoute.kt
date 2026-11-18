package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.configuration.Host
import java.time.Duration

/**
 * The [SshRoute] every SSH client is given: it selects the real route the first time a host is
 * reached, not when it is built.
 *
 * Selecting the route reads the profile's SSH transport, so it loads the AWS profile. Many services
 * that can reach a node (the remote operations, the SOCKS tunnel, Grafana and the backends' HTTP
 * access) are built by commands that also run in a local workspace, where there is no profile and no
 * node is ever dialed over SSH. Deferring the choice keeps building those services free of the
 * profile. A route that was never used is closed without being selected.
 *
 * @param select builds the route for the profile's transport; called at most once
 */
class DeferredSshRoute(
    select: () -> SshRoute,
) : SshRoute {
    private val selected = lazy(select)

    private val route: SshRoute get() = selected.value

    override fun endpoint(host: Host): SshEndpoint = route.endpoint(host)

    override fun proxyCommand(host: Host): String? = route.proxyCommand(host)

    override val tunnelVerifyAttempts: Int get() = route.tunnelVerifyAttempts

    override fun tunnelConnectFailureHint(sshOpenToAll: Boolean): String? = route.tunnelConnectFailureHint(sshOpenToAll)

    override val authTimeout: Duration? get() = route.authTimeout

    override fun invalidate(host: Host) = route.invalidate(host)

    override fun close() {
        if (selected.isInitialized()) selected.value.close()
    }
}
