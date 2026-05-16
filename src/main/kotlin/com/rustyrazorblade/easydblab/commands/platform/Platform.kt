package com.rustyrazorblade.easydblab.commands.platform

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

@Command(
    name = "platform",
    description = ["K8s platform substrate operations"],
    mixinStandardHelpOptions = true,
    subcommands = [
        PlatformCreatePvs::class,
        PlatformInfo::class,
    ],
)
class Platform : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
