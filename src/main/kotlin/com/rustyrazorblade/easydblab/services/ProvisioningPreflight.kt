package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus

/**
 * The checks `up` runs before it provisions any AWS resource, so a configuration that cannot
 * produce a working cluster fails at once and stands up nothing.
 */
class ProvisioningPreflight(
    private val localTailscaleClient: LocalTailscaleClient,
    private val eventBus: EventBus,
    private val localSsmTooling: LocalSsmTooling,
    private val userConfig: User,
) {
    /** Fails when [state] or its [initConfig] cannot produce a cluster this machine can reach. */
    fun verify(
        state: ClusterState,
        initConfig: InitConfig,
    ) {
        verifyControlNode(initConfig)
        verifyTelemetryRedirect(initConfig)
        verifyLocalTailscale(state)
        verifyLocalSsmTooling()
    }

    private fun toEventFault(fault: SsmToolFault): Event.Ssh.SsmToolsMissing.ToolFault {
        val executable = fault.tool.executable
        return when (fault) {
            is SsmToolFault.NotFound ->
                Event.Ssh.SsmToolsMissing.ToolFault(
                    executable,
                    Event.Ssh.SsmToolsMissing.Reason.NotFound,
                    installHint = fault.tool.installHint,
                )
            is SsmToolFault.Failed ->
                Event.Ssh.SsmToolsMissing.ToolFault(
                    executable,
                    Event.Ssh.SsmToolsMissing.Reason.Failed,
                    exitCode = fault.exitCode,
                    output = fault.output,
                )
            is SsmToolFault.TimedOut ->
                Event.Ssh.SsmToolsMissing.ToolFault(
                    executable,
                    Event.Ssh.SsmToolsMissing.Reason.TimedOut,
                    timeoutSeconds = fault.timeout.toSeconds(),
                )
        }
    }

    /**
     * Validates that the programs the `ssm` SSH transport needs are on THIS machine.
     *
     * Under `ssm` every SSH connection, `up`'s own readiness wait included, is opened by the AWS CLI
     * through the Session Manager plugin. Without them that fault would surface only after the
     * instances were running, as an SSH readiness timeout that says nothing about SSM.
     */
    private fun verifyLocalSsmTooling() {
        if (userConfig.sshTransport != SshTransport.Ssm) return

        val faults = localSsmTooling.faults()
        if (faults.isEmpty()) return

        eventBus.emit(Event.Ssh.SsmToolsMissing(faults.map(::toEventFault)))
        error(
            "This profile's SSH transport is ssm, but " +
                faults.joinToString(" and ") { "'${it.tool.executable}'" } +
                " could not be run on this machine. Fix the tools listed above, then run 'easy-db-lab up' again.",
        )
    }

    /**
     * Validates that the configuration will produce a control node. Every downstream step — K3s,
     * node labeling, StorageClasses, observability — depends on a control node existing.
     * Discovering that it doesn't mid-provisioning, after EC2 instances are already running, is
     * the exact failure this check exists to prevent.
     */
    private fun verifyControlNode(initConfig: InitConfig) {
        if (initConfig.controlInstances < 1) {
            eventBus.emit(Event.Provision.ControlNodeRequired(initConfig.controlInstances))
            error(
                "A control node is required to provision a cluster " +
                    "(configured control instances: ${initConfig.controlInstances}).",
            )
        }
    }

    /**
     * Re-validates the telemetry redirect endpoints.
     *
     * `init` already validates at init time, but state.json can be hand-edited between init and up.
     * Re-checking here defends that path: a redirect cluster must fail fast, naming the offending
     * signal, and stand up nothing — never a partial or mixed local/external stack. Well-formedness
     * only; unreachable-but-well-formed endpoints surface later as collector send failures.
     */
    private fun verifyTelemetryRedirect(initConfig: InitConfig) {
        val offending = initConfig.telemetryRedirect?.validate().orEmpty()
        if (offending.isNotEmpty()) {
            eventBus.emit(Event.Provision.TelemetryRedirectInvalid(offending))
            error(
                "Telemetry redirect endpoints are missing or malformed for: " +
                    "${offending.joinToString(", ")}.",
            )
        }
    }

    /**
     * Validates that the Tailscale client on THIS machine can carry traffic.
     *
     * A Tailscale cluster routes every connection over the tailnet and starts no SOCKS tunnel, so an
     * operator whose own machine is logged out has no route to the cluster at all. Without this check
     * that fault surfaces only after four instances and a K3s install, as a 30-second Fabric8 connect
     * timeout naming a private IP — a symptom that says nothing about Tailscale. The check costs one
     * local process invocation.
     */
    private fun verifyLocalTailscale(state: ClusterState) {
        if (!state.isTailscaleEnabled()) return

        when (val local = localTailscaleClient.state()) {
            is LocalTailscaleState.Connected -> return
            is LocalTailscaleState.NotInstalled -> {
                eventBus.emit(Event.Tailscale.LocalClientNotInstalled)
                error(
                    "Tailscale is enabled for this cluster, but the 'tailscale' command was not found on this machine. " +
                        "Every connection to the cluster is routed over the tailnet, so provisioning cannot reach it. " +
                        "Install Tailscale from https://tailscale.com/download and run 'tailscale up', " +
                        "then run 'easy-db-lab up' again. " +
                        "If you installed the macOS App Store build, its CLI is not on the PATH: " +
                        "see https://tailscale.com/kb/1080/cli.",
                )
            }
            is LocalTailscaleState.Disconnected -> {
                eventBus.emit(Event.Tailscale.LocalClientDisconnected(local.backendState))
                error(
                    "Tailscale is enabled for this cluster, but the local Tailscale client is not connected " +
                        "(state: ${local.backendState}). " +
                        "Every connection to the cluster is routed over the tailnet, so provisioning has no route to it. " +
                        "Run 'tailscale up' on this machine, confirm 'tailscale status' reports it connected, " +
                        "then run 'easy-db-lab up' again.",
                )
            }
        }
    }
}
