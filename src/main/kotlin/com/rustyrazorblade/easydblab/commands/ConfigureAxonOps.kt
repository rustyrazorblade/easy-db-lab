package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import kotlin.system.exitProcess

/**
 * Setup / configure axon-agent for use with the Cassandra cluster.
 */
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "configure-axonops",
    description = ["Setup / configure axon-agent for use with the Cassandra cluster"],
)
class ConfigureAxonOps : PicoBaseCommand() {
    private val userConfig: User by inject()
    private val hostOperationsService: HostOperationsService by inject()

    @Option(names = ["--org"], description = ["AxonOps Organization Name"])
    var org = ""

    @Option(names = ["--key"], description = ["AxonOps API Key"])
    var key = ""

    @Mixin
    var hosts = HostsMixin()

    override fun execute() {
        val axonOrg = if (org.isNotBlank()) org else userConfig.axonOpsOrg
        val axonKey = if (key.isNotBlank()) key else userConfig.axonOpsKey
        if ((axonOrg.isBlank() || axonKey.isBlank())) {
            eventBus.emit(Event.Setup.AxonOpsMissingArgs)
            exitProcess(1)
        }

        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList) { host ->
            val it = host.toHost()
            eventBus.emit(Event.Setup.AxonOpsConfiguring(it.toString()))

            remoteOps
                .executeRemotely(
                    it,
                    "/usr/local/bin/setup-axonops $axonOrg $axonKey",
                    secret = true,
                ).text
        }
    }
}
