package com.rustyrazorblade.easydblab.commands.report

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for a test's documents: the operator's markdown notes and results, stored with the
 * test in the account bucket and shown on the Tests and comparison dashboards.
 *
 * Available sub-commands:
 * - upload: store markdown files in the test's folder and rebuild its index
 */
@Command(
    name = "report",
    description = ["Attach documents to the current test"],
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
