package com.rustyrazorblade.easydblab.commands.metrics

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for metrics operations.
 *
 * This command provides a unified interface for managing VictoriaMetrics:
 * - Backup metrics data to S3
 *
 * VictoriaMetrics runs on the control node and stores metrics from all nodes
 * in the cluster via OpenTelemetry.
 *
 * Available sub-commands:
 * - backup: Backup VictoriaMetrics data to S3
 */
@Command(
    name = "metrics",
    description = ["Manage VictoriaMetrics observability data"],
    mixinStandardHelpOptions = true,
    subcommands = [
        MetricsBackup::class,
        MetricsImport::class,
        MetricsLs::class,
    ],
)
class Metrics : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
