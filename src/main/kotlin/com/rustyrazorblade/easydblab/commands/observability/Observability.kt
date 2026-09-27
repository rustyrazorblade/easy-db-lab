package com.rustyrazorblade.easydblab.commands.observability

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for the account-wide observability store, which outlives every cluster.
 *
 * Available sub-commands:
 * - compactor: start, stop and inspect the account compactor
 */
@Command(
    name = "observability",
    description = ["Account-wide observability store operations"],
    mixinStandardHelpOptions = true,
    subcommands = [Compactor::class],
)
class Observability : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}

/**
 * Parent command for the account compactor: the ECS Fargate service that compacts the shared
 * store. `up` starts it and the last cluster's `down` stops it; these commands do the same by hand.
 */
@Command(
    name = "compactor",
    description = ["Start, stop and inspect the account compactor"],
    mixinStandardHelpOptions = true,
    subcommands = [CompactorStart::class, CompactorStop::class, CompactorStatus::class],
)
class Compactor : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
