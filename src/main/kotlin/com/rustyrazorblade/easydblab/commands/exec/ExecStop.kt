package com.rustyrazorblade.easydblab.commands.exec

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.converters.PicoServerTypeConverter
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "stop",
    description = ["Stop a running background tool on remote hosts"],
)
class ExecStop : PicoBaseCommand() {
    private val hostOperationsService: HostOperationsService by inject()

    @Mixin
    var hosts = HostsMixin()

    @Parameters(
        description = ["Name of the tool to stop (without edl-exec- prefix)"],
        arity = "1",
    )
    lateinit var name: String

    @Option(
        names = ["--type", "-t"],
        description = ["Server type (cassandra, stress, control). Stops on all types if not specified."],
        converter = [PicoServerTypeConverter::class],
    )
    var serverType: ServerType? = null

    override fun execute() {
        val unitName = "edl-exec-$name"
        val types = serverType?.let { listOf(it) } ?: listOf(ServerType.Cassandra, ServerType.Stress, ServerType.Control)

        for (type in types) {
            hostOperationsService.withHosts(clusterState.hosts, type, hosts.hostList, parallel = true) { host ->
                val h = host.toHost()
                try {
                    remoteOps.executeRemotely(h, "sudo systemctl stop $unitName", output = false, secret = false)
                    eventBus.emit(Event.Command.ToolStopped(h.alias, unitName))
                } catch (e: Exception) {
                    eventBus.emit(
                        Event.Command.ToolStopError(
                            h.alias,
                            unitName,
                            e.message ?: "Unknown error",
                        ),
                    )
                }
            }
        }
    }
}
