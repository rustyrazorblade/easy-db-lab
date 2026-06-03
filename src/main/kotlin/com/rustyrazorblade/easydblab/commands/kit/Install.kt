package com.rustyrazorblade.easydblab.commands.kit

import com.rustyrazorblade.easydblab.commands.install.BaseInstallCommand
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Spec
import java.nio.file.Path

/**
 * Installs a kit by scaffolding its template files into the current working directory.
 *
 * Built-in kits (e.g. clickhouse, presto) are registered as dynamic subcommands of this
 * command at startup by
 * [com.rustyrazorblade.easydblab.CommandLineParser.registerDynamicInstallSubcommands].
 * Ad-hoc kits can be installed via [from], [kit], and [size] without a named subcommand.
 *
 * When invoked with no flags or subcommand, help is printed listing all available kits.
 */
@Command(
    name = "install",
    description = ["Scaffold kit installation files from built-in or custom templates"],
    mixinStandardHelpOptions = true,
)
class Install : BaseInstallCommand() {
    @Spec
    lateinit var spec: CommandSpec

    @Option(
        names = ["--from"],
        description = ["Path to a custom (ad-hoc) template directory"],
    )
    var from: Path? = null

    @Option(
        names = ["--kit"],
        description = ["Kit name — used as output directory and substituted as __KIT_NAME__"],
    )
    var kit: String? = null

    @Option(
        names = ["--size"],
        description = ["Storage size per node (e.g. 100Gi) substituted as __STORAGE_SIZE__"],
    )
    var size: String? = null

    override fun execute() {
        when {
            from != null -> {
                val fromPath = from!!
                val kitName = kit ?: error("--kit is required with --from")
                val storageSize = size ?: error("--size is required with --from")
                val source = resolver.resolveAdHoc(fromPath)
                renderAndWrite(source, kitName, storageSize)
            }

            else -> spec.commandLine().usage(System.out)
        }
    }
}
