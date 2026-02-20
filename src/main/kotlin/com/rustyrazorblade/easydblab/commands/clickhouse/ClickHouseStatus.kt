package com.rustyrazorblade.easydblab.commands.clickhouse

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.s3Path
import com.rustyrazorblade.easydblab.output.displayClickHouseAccess
import com.rustyrazorblade.easydblab.output.displayS3ManagerClickHouseAccess
import com.rustyrazorblade.easydblab.services.K8sService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Check the status of the ClickHouse cluster on K8s.
 *
 * Displays the current state of all ClickHouse pods including
 * Keeper nodes and server nodes.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "status",
    description = ["Check ClickHouse cluster status"],
)
class ClickHouseStatus : PicoBaseCommand() {
    private val log = KotlinLogging.logger {}
    private val k8sService: K8sService by inject()

    override fun execute() {
        val controlHosts = clusterState.hosts[ServerType.Control]
        if (controlHosts.isNullOrEmpty()) {
            error("No control nodes found. Please ensure the environment is running.")
        }
        val controlNode = controlHosts.first()
        log.debug { "Using control node: ${controlNode.alias} (${controlNode.publicIp})" }

        // Get a db node IP for ClickHouse access (ClickHouse pods run on db nodes)
        val dbHosts = clusterState.hosts[ServerType.Cassandra]
        if (dbHosts.isNullOrEmpty()) {
            error("No db nodes found. Please ensure the environment is running.")
        }
        val dbNodeIp = dbHosts.first().privateIp

        val status =
            k8sService
                .getNamespaceStatus(controlNode, Constants.ClickHouse.NAMESPACE)
                .getOrElse { exception ->
                    error("Failed to get ClickHouse status: ${exception.message}")
                }

        outputHandler.handleMessage("ClickHouse Cluster Status:")
        outputHandler.handleMessage("")
        outputHandler.handleMessage(status)
        outputHandler.displayClickHouseAccess(dbNodeIp)

        if (!clusterState.s3Bucket.isNullOrBlank()) {
            outputHandler.displayS3ManagerClickHouseAccess(controlNode.privateIp, clusterState.s3Path())
        }
    }
}
