package com.rustyrazorblade.easydblab.commands.aws

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for AWS-related operations.
 *
 * This command groups subcommands for AWS resource discovery and management:
 * - vpcs: List easy-db-lab VPCs
 */
@Command(
    name = "aws",
    description = ["AWS resource discovery and management operations"],
    mixinStandardHelpOptions = true,
    subcommands = [
        Region::class,
        S3Bucket::class,
        Vpcs::class,
    ],
)
class Aws : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
