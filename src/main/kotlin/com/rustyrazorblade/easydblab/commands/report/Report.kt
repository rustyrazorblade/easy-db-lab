package com.rustyrazorblade.easydblab.commands.report

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for an experiment's reports: directories of the operator's notes, results and
 * graphs, stored with the experiment in the account bucket and shown on the Tests and comparison
 * dashboards of every cluster of the experiment.
 *
 * Available sub-commands:
 * - upload: zip one directory and store it as a new report of the experiment
 */
@Command(
    name = "report",
    description = ["Upload reports of the current experiment"],
    mixinStandardHelpOptions = true,
    subcommands = [
        ReportUpload::class,
    ],
)
class Report : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
