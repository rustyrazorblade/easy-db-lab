package com.rustyrazorblade.easydblab.commands.aws

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoCommand
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Get the AWS region for the current cluster.
 *
 * This command outputs the region from the cluster configuration,
 * which is also used as the Cassandra datacenter name.
 *
 * Examples:
 *   easy-db-lab aws region  # Returns region (e.g., us-west-2)
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "region",
    description = ["Get AWS region for the current cluster"],
)
class Region :
    PicoCommand,
    KoinComponent {
    private val eventBus: EventBus by inject()
    private val clusterStateManager: ClusterStateManager by inject()
    private val clusterState by lazy { clusterStateManager.load() }

    override fun execute() {
        val region =
            clusterState.initConfig?.region
                ?: error("No region configured. Run 'easy-db-lab init' first.")
        eventBus.emit(Event.Command.RegionName(region))
    }
}
