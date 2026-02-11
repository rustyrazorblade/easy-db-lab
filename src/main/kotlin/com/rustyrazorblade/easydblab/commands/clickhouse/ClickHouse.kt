package com.rustyrazorblade.easydblab.commands.clickhouse

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for ClickHouse operations on K8s.
 *
 * This command serves as a container for ClickHouse-related sub-commands including
 * starting, stopping, and checking status of ClickHouse clusters deployed on K3s.
 *
 * Available sub-commands:
 * - start: Deploy ClickHouse cluster to K8s
 * - status: Check ClickHouse cluster status
 * - stop: Remove ClickHouse cluster from K8s
 */
@Command(
    name = "clickhouse",
    description = ["ClickHouse cluster operations on K8s"],
    mixinStandardHelpOptions = true,
    subcommands = [
        ClickHouseGenerateDashboards::class,
        ClickHouseInit::class,
        ClickHouseStart::class,
        ClickHouseStatus::class,
        ClickHouseStop::class,
    ],
)
class ClickHouse : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
