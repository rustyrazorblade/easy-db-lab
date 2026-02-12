package com.rustyrazorblade.easydblab.commands.dashboards

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for Grafana dashboard operations.
 *
 * This command serves as a container for dashboard-related sub-commands including
 * generating (extracting) dashboard manifests from JAR resources and uploading
 * them to the K8s cluster.
 *
 * Available sub-commands:
 * - generate: Extract all Grafana dashboard manifests from JAR resources
 * - upload: Apply all dashboard manifests and datasource ConfigMap to K8s cluster
 */
@Command(
    name = "dashboards",
    description = ["Grafana dashboard operations"],
    mixinStandardHelpOptions = true,
    subcommands = [
        DashboardsGenerate::class,
        DashboardsUpload::class,
    ],
)
class Dashboards : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
