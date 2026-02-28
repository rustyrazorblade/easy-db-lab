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

@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "list",
    description = ["List running background tools on remote hosts"],
)
class ExecList : PicoBaseCommand() {
    private val hostOperationsService: HostOperationsService by inject()

    @Mixin
    var hosts = HostsMixin()

    @Option(
        names = ["--type", "-t"],
        description = ["Server type (cassandra, stress, control). Lists all types if not specified."],
        converter = [PicoServerTypeConverter::class],
    )
    var serverType: ServerType? = null

    override fun execute() {
        val types = serverType?.let { listOf(it) } ?: listOf(ServerType.Cassandra, ServerType.Stress, ServerType.Control)

        for (type in types) {
            hostOperationsService.withHosts(clusterState.hosts, type, hosts.hostList, parallel = true) { host ->
                val h = host.toHost()
                try {
                    val result =
                        remoteOps.executeRemotely(
                            h,
                            "systemctl list-units 'edl-exec-*' --no-pager --plain --no-legend",
                            output = false,
                            secret = false,
                        )
                    val output = result.text.trim()
                    if (output.isNotEmpty()) {
                        eventBus.emit(Event.Command.ToolList(h.alias, output))
                    }
                } catch (_: Exception) {
                    // Host may not be reachable, skip silently
                }
            }
        }
    }
}
