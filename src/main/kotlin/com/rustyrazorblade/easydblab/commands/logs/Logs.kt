package com.rustyrazorblade.easydblab.commands.logs

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for log operations.
 *
 * This command provides a unified interface for querying logs from all sources:
 * - Cassandra logs (/var/log/cassandra/)
 * - ClickHouse logs (/mnt/db1/clickhouse/logs/)
 * - systemd/journald (cassandra.service, docker.service, etc.)
 * - System logs (/var/log/)
 * - EMR/Spark logs
 *
 * Logs are collected by OTel and stored in Loki on the control node, which writes them to S3 as it
 * runs; there is no separate logs backup.
 *
 * Available sub-commands:
 * - query: Query logs from Loki
 */
@Command(
    name = "logs",
    description = ["Query logs from Loki"],
    mixinStandardHelpOptions = true,
    subcommands = [
        LogsQuery::class,
    ],
)
class Logs : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
