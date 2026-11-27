package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.profile.ProfileAccess
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
    private val profile: ProfileAccess by inject()
    private val hostOperationsService: HostOperationsService by inject()

    @Option(names = ["--org"], description = ["AxonOps Organization Name"])
    var org = ""

    @Option(names = ["--key"], description = ["AxonOps API Key"])
    var key = ""

    @Mixin
    var hosts = HostsMixin()

    override fun execute() {
        val saved = profile.current().axonOps
        val axonOrg = org.ifBlank { saved?.org.orEmpty() }
        val axonKey = key.ifBlank { saved?.key.orEmpty() }

        if ((axonOrg.isBlank() || axonKey.isBlank())) {
            eventBus.emit(Event.Setup.AxonOpsMissingArgs)
            exitProcess(1)
        }

        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList, parallel = true) { host ->
            val it = host.toHost()
            eventBus.emit(Event.Setup.AxonOpsConfiguring(it.alias))

            remoteOps
                .executeRemotely(
                    it,
                    "/usr/local/bin/setup-axonops $axonOrg $axonKey",
                    secret = true,
                ).text
        }
    }
}
