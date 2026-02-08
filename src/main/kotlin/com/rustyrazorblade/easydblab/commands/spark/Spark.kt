package com.rustyrazorblade.easydblab.commands.spark

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for Spark EMR cluster operations.
 *
 * This command serves as a container for Spark-related sub-commands including
 * cluster provisioning, job submission, status checking, and job listing.
 * When invoked without a sub-command, it displays usage help.
 *
 * Available sub-commands:
 * - init: Provision a Spark EMR cluster on an existing environment
 * - submit: Submit a Spark job to the EMR cluster
 * - status: Check the status of a Spark job (defaults to most recent)
 * - jobs: List recent Spark jobs on the cluster
 * - logs: View logs from a Spark job
 */
@Command(
    name = "spark",
    description = ["Spark EMR cluster operations"],
    mixinStandardHelpOptions = true,
    subcommands = [
        SparkInit::class,
        SparkSubmit::class,
        SparkStatus::class,
        SparkJobs::class,
        SparkLogs::class,
    ],
)
class Spark : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
