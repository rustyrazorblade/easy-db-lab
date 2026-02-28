package com.rustyrazorblade.easydblab.commands.exec

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

@Command(
    name = "exec",
    description = ["Execute commands on remote hosts with systemd logging"],
    mixinStandardHelpOptions = true,
    subcommands = [
        ExecRun::class,
        ExecList::class,
        ExecStop::class,
    ],
)
class Exec : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
