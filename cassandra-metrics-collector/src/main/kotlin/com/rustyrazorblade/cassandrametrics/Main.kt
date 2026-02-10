package com.rustyrazorblade.cassandrametrics

import picocli.CommandLine
import kotlin.system.exitProcess

@CommandLine.Command(
    name = "cassandra-metrics-collector",
    description = ["Collects metrics from Cassandra virtual tables and exposes them as a Prometheus endpoint"],
    mixinStandardHelpOptions = true,
    subcommands = [ServerCommand::class],
)
class Main

fun main(args: Array<String>) {
    val exitCode = CommandLine(Main()).execute(*args)
    exitProcess(exitCode)
}
