package com.rustyrazorblade.easydblab.commands.grafana

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for Grafana operations.
 *
 * Available sub-commands:
 * - upload: Build and apply all Grafana dashboard manifests and datasource ConfigMap to K8s cluster
 */
@Command(
    name = "grafana",
    description = ["Grafana operations"],
    mixinStandardHelpOptions = true,
    subcommands = [
        GrafanaUpload::class,
    ],
)
class Grafana : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
