package com.rustyrazorblade.easydblab.commands.socks

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for the workspace's SOCKS5 tunnel to the cluster.
 *
 * Every command that needs the tunnel starts it on its own; this group is for what runs outside the
 * CLI, such as the tool wrappers in `<workspace>/bin/` and a browser, which need a recorded tunnel.
 *
 * Available sub-commands:
 * - start: Start the tunnel, or keep the running one, and record its port
 * - stop: Stop the tunnel without tearing the cluster down
 *
 * Usage:
 * ```
 * easy-db-lab socks start
 * easy-db-lab socks stop
 * ```
 */
@Command(
    name = "socks",
    description = ["SOCKS5 tunnel to the cluster, for tools that run outside the CLI"],
    mixinStandardHelpOptions = true,
    subcommands = [
        SocksStart::class,
        SocksStop::class,
    ],
)
class Socks : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
