package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.events.Event
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Spec
import java.nio.file.Path

@Command(
    name = "install",
    description = ["Scaffold workload installation files from built-in or custom templates"],
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
        names = ["--workload"],
        description = ["Workload name — used as output directory and substituted as __WORKLOAD_NAME__"],
    )
    var workload: String? = null

    @Option(
        names = ["--size"],
        description = ["Storage size per node (e.g. 100Gi) substituted as __STORAGE_SIZE__"],
    )
    var size: String? = null

    @Option(
        names = ["--list"],
        description = ["List all discoverable install templates (profile + built-in)"],
    )
    var list: Boolean = false

    override fun execute() {
        when {
            list -> {
                val templates = resolver.listAvailableTemplateDetails()
                eventBus.emit(Event.Install.TemplatesListed(templates))
            }

            from != null -> {
                val fromPath = from ?: error("--from path is null")
                val workloadName = workload ?: error("--workload is required with --from")
                val storageSize = size ?: error("--size is required with --from")
                val source = resolver.resolveAdHoc(fromPath)
                renderAndWrite(source, workloadName, storageSize)
            }

            else -> spec.commandLine().usage(System.out)
        }
    }
}
