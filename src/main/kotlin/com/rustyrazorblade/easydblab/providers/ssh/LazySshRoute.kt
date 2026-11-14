package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.configuration.Host
import java.time.Duration

/**
 * An [SshRoute] that picks its transport the first time something uses it.
 *
 * The transport comes from the profile's `settings.yaml`. `profile setup` is the command that
 * writes that file, yet Koin builds the services it needs, the SOCKS proxy among them, before the
 * command runs. A route that read the profile when it was built made `profile setup` fail on a
 * profile that did not exist yet. Deferring the read to the first use lets setup start; any
 * command that really connects reads the profile at that point, and still fails there with the
 * "run profile setup" message when the profile is missing.
 *
 * @param resolve builds the real route; called at most once, on the first use
 */
class LazySshRoute(
    resolve: () -> SshRoute,
) : SshRoute {
    private val route: SshRoute by lazy(resolve)

    /** Whether the real route was built, so [close] does not build one just to close it. */
    @Volatile
    private var resolved = false

    private val target: SshRoute
        get() = route.also { resolved = true }

    override fun endpoint(host: Host): SshEndpoint = target.endpoint(host)

    override fun proxyCommand(host: Host): String? = target.proxyCommand(host)

    override val tunnelVerifyAttempts: Int
        get() = target.tunnelVerifyAttempts

    override fun tunnelConnectFailureHint(sshOpenToAll: Boolean): String? = target.tunnelConnectFailureHint(sshOpenToAll)

    override val authTimeout: Duration?
        get() = target.authTimeout

    override fun invalidate(host: Host) = target.invalidate(host)

    override fun close() {
        if (resolved) route.close()
    }
}
