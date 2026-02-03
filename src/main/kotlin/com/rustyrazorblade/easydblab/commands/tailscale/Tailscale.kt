package com.rustyrazorblade.easydblab.commands.tailscale

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for Tailscale VPN operations.
 *
 * Tailscale provides secure mesh VPN access to the cluster control node,
 * which can then be used as a jump host to access all other nodes.
 *
 * Available sub-commands:
 * - start: Start Tailscale and authenticate on the control node
 * - stop: Disconnect and stop Tailscale on the control node
 * - status: Show Tailscale connection status
 *
 * Prerequisites:
 * - Tailscale OAuth credentials configured via setup-profile or CLI args
 * - A running cluster with a control node
 *
 * Usage:
 * ```
 * # Start Tailscale (uses credentials from setup-profile)
 * easy-db-lab tailscale start
 *
 * # Start with explicit credentials
 * easy-db-lab tailscale start --client-id tskey-client-xxx --client-secret tskey-xxx
 *
 * # Check status
 * easy-db-lab tailscale status
 *
 * # Stop Tailscale
 * easy-db-lab tailscale stop
 * ```
 */
@Command(
    name = "tailscale",
    description = ["Tailscale VPN operations for secure cluster access"],
    mixinStandardHelpOptions = true,
    subcommands = [
        TailscaleStart::class,
        TailscaleStop::class,
        TailscaleStatus::class,
    ],
)
class Tailscale : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
