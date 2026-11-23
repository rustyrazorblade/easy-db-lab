package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.profile.ProfileAccess
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.network.TcpReachabilityProbe
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.proxy.SocksProxyState
import com.rustyrazorblade.easydblab.services.ClusterConfigurationService
import com.rustyrazorblade.easydblab.services.bringup.ClusterStateWriter
import com.rustyrazorblade.easydblab.services.bringup.OperatorRoute
import io.github.resilience4j.retry.Retry
import java.io.File
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * `up`'s workspace files: `sshConfig`, `env.sh`, `environment.sh` and the AxonOps workbench file,
 * from the hosts in [state], then `cassandra.patch.yaml` through [writeConfig]. The control
 * pipeline writes them once every instance is launched, because the tunnel reads `sshConfig`; the
 * post-step writes them again with the final hosts.
 */
class UpWorkspace(
    private val configService: ClusterConfigurationService,
    private val state: ClusterStateWriter,
    private val directory: File,
    private val writeConfig: () -> Unit,
) {
    /** Writes every workspace file for the hosts known now. */
    fun write() {
        state.read { configService.writeAllConfigurationFiles(directory.toPath(), it).getOrThrow() }
        writeConfig()
    }
}

/**
 * The operator's side of the control pipeline (design D2): the workspace files once [launched]
 * completes (every launch returned, so every host is known), then [connect], the route this
 * cluster uses: the tailnet ([TailnetRoute]), the SOCKS tunnel ([SocksRoute]), or none.
 */
class UpOperatorRoute(
    private val workspace: UpWorkspace,
    private val launched: CompletableFuture<Unit>,
    private val connect: (ClusterHost) -> Unit,
) : OperatorRoute {
    override fun writeWorkspace() {
        launched.get()
        workspace.write()
    }

    override fun connect(control: ClusterHost) = connect.invoke(control)
}

/**
 * The SOCKS tunnel to the control node, for a cluster that does not use Tailscale. The service is
 * always asked, so a tunnel that died since the `@RequiresProxy` preflight is started again. The
 * tunnel is reported once, as `socks start` reports it: when the service reports the tunnel the
 * preflight ([preflightTunnel]) already started or reused (the same control node and port), the
 * `reused` flag is the preflight's, because the service would now call a tunnel it just started
 * "already running".
 */
class SocksRoute(
    private val socksProxyService: SocksProxyService,
    private val eventBus: EventBus,
    private val preflightTunnel: SocksProxyState?,
) {
    /** Starts or reuses the tunnel to [control] and reports it. */
    fun connect(control: ClusterHost) {
        val proxy = socksProxyService.ensureRunning(control)
        // Only the same live tunnel keeps the preflight's flag: one the recheck had to start again is new, even on the same port.
        val fromPreflight =
            preflightTunnel?.takeIf {
                proxy.reused && it.gatewayHost.privateIp == control.privateIp && it.localPort == proxy.localPort
            }
        eventBus.emit(Event.Proxy.TunnelReady(port = proxy.localPort, reused = fromPreflight?.reused ?: proxy.reused))
    }
}

/**
 * The tailnet route to the control node: Tailscale started on it when the profile has the
 * credentials ([startTailscale] runs `tailscale start` and returns its exit code), then a wait
 * until this machine reaches the control node's private IP.
 *
 * `tailscale start` saves the auth key and device IDs to `state.json` itself, so they are copied
 * from [manager] into [state] at once: the next save through [state] would otherwise drop them.
 *
 * The control node joining the tailnet says nothing about whether this machine has a route to it:
 * the subnet route it advertises has to be approved, and it takes a while to reach the operator's
 * own client. The probe dials the control node's `sshd`, which is listening, having just run
 * setup; it repeats every [retryInterval] up to [Constants.Tailscale.REACHABILITY_MAX_ATTEMPTS]
 * times.
 */
class TailnetRoute(
    private val profile: ProfileAccess,
    private val eventBus: EventBus,
    private val probe: TcpReachabilityProbe,
    private val retryInterval: Duration,
    private val startTailscale: () -> Int,
    private val state: ClusterStateWriter,
    private val manager: ClusterStateManager,
) {
    /** Starts Tailscale on [control] if configured, then waits for the route to it. */
    fun connect(control: ClusterHost) {
        if (profile.current().isTailscaleEnabled) start()

        awaitRoute(control)
    }

    private fun start() {
        eventBus.emit(Event.Provision.TailscaleStarting)
        val exitCode = startTailscale()
        check(exitCode == 0) {
            "Failed to start Tailscale VPN (exit code $exitCode). You can manually start it later with: easy-db-lab tailscale start"
        }
        val saved = manager.load()
        state.update {
            it.tailscaleAuthKeyId = saved.tailscaleAuthKeyId
            it.tailscaleDeviceId = saved.tailscaleDeviceId
        }
    }

    private fun awaitRoute(control: ClusterHost) {
        val port = Constants.Network.SSH_PORT
        val maxAttempts = Constants.Tailscale.REACHABILITY_MAX_ATTEMPTS
        val retry = Retry.of("tailnet-reachability", RetryUtil.createBooleanPollRetryConfig(maxAttempts, retryInterval))
        retry.eventPublisher.onRetry { event ->
            eventBus.emit(Event.Tailscale.RouteWaiting(control.alias, control.privateIp, port, event.numberOfRetryAttempts, maxAttempts))
        }
        if (retry.executeSupplier { probe.isReachable(control.privateIp, port) }) return

        eventBus.emit(Event.Tailscale.ControlNodeUnreachable(control.alias, control.privateIp, port))
        val cidr = state.read { it.initConfig?.cidr } ?: Constants.Vpc.DEFAULT_CIDR
        val waited = retryInterval.multipliedBy((maxAttempts - 1).toLong()).toSeconds()
        error(
            "Tailscale started on ${control.alias}, but this machine cannot reach it at " +
                "${control.privateIp}:$port over the tailnet after $maxAttempts attempts over $waited seconds. " +
                "The control node joined the tailnet, so the missing piece is the route from this machine. " +
                "Confirm 'tailscale status' on this machine lists ${control.alias}, and approve the subnet " +
                "route $cidr for ${control.alias} at https://login.tailscale.com/admin/machines. " +
                "Then run 'easy-db-lab up' again.",
        )
    }
}
