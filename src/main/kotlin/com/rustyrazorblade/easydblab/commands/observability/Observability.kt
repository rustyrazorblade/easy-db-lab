package com.rustyrazorblade.easydblab.commands.observability

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for the account-wide observability store, which outlives every cluster.
 *
 * Available sub-commands:
 * - compactor: inspect the account compactor's lease
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
 * Parent command for the account compactor, which runs in a pod on every cluster and compacts the
 * shared store only while it holds the store lease. There is nothing to start or stop by hand.
 */
@Command(
    name = "compactor",
    description = ["Inspect the account compactor"],
    mixinStandardHelpOptions = true,
    subcommands = [CompactorStatus::class],
)
class Compactor : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
