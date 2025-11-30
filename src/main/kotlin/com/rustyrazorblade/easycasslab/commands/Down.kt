package com.rustyrazorblade.easycasslab.commands

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rustyrazorblade.easycasslab.Context
import com.rustyrazorblade.easycasslab.annotations.McpCommand
import com.rustyrazorblade.easycasslab.annotations.RequireProfileSetup
import com.rustyrazorblade.easycasslab.configuration.ClusterStateManager
import com.rustyrazorblade.easycasslab.providers.aws.InfrastructureTeardownService
import com.rustyrazorblade.easycasslab.providers.aws.TeardownMode
import com.rustyrazorblade.easycasslab.providers.aws.TeardownResult
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File
import java.util.Scanner

/**
 * Shut down AWS infrastructure using direct AWS API calls.
 *
 * This command provides multiple modes of operation:
 * - Default: Tear down the current cluster's VPC (from cluster state)
 * - VPC ID: Tear down a specific VPC by ID
 * - --all: Tear down all VPCs tagged with easy_cass_lab
 * - --packer: Tear down the packer infrastructure VPC
 *
 * Resources are deleted in the correct dependency order:
 * EMR Clusters -> EC2 Instances -> NAT Gateways -> Security Groups ->
 * Route Tables -> Subnets -> Internet Gateway -> VPC
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "down",
    description = ["Shut down AWS infrastructure"],
)
@Suppress("TooManyFunctions")
class Down(
    context: Context,
) : PicoBaseCommand(context) {
    @Parameters(
        index = "0",
        arity = "0..1",
        description = ["Optional VPC ID to tear down a specific VPC"],
    )
    var vpcId: String? = null

    @Option(
        names = ["--all"],
        description = ["Tear down all VPCs tagged with easy_cass_lab"],
    )
    var teardownAll = false

    @Option(
        names = ["--packer"],
        description = ["Tear down the packer infrastructure VPC"],
    )
    var teardownPacker = false

    @Option(
        names = ["--dry-run"],
        description = ["Preview what would be deleted without actually deleting"],
    )
    var dryRun = false

    @Option(
        names = ["--auto-approve", "-a", "--yes"],
        description = ["Auto approve changes without confirmation prompt"],
    )
    var autoApprove = false

    private val clusterStateManager: ClusterStateManager by inject()
    private val teardownService: InfrastructureTeardownService by inject()
    private val log = KotlinLogging.logger {}

    data class Socks5ProxyState(
        val pid: Int,
        val port: Int,
        val controlHost: String,
        val controlIP: String,
        val clusterName: String,
        val startTime: String,
        val sshConfig: String,
    )

    override fun execute() {
        val mode = determineTeardownMode()

        outputHandler.handleMessage("Crushing dreams, terminating instances.")

        // Cleanup local resources first
        cleanupSocks5Proxy()

        // Execute the teardown based on mode
        val result = executeTeardown(mode)

        // Update cluster state if we're tearing down the current cluster
        if (mode == TeardownMode.CurrentCluster || mode is TeardownMode.SpecificVpc) {
            updateClusterState()
        }

        // Report results
        reportResult(result)
    }

    /**
     * Determines the teardown mode based on command line options.
     */
    private fun determineTeardownMode(): TeardownMode =
        when {
            vpcId != null -> TeardownMode.SpecificVpc(vpcId!!)
            teardownAll -> TeardownMode.AllTagged
            teardownPacker -> TeardownMode.PackerInfrastructure
            else -> TeardownMode.CurrentCluster
        }

    /**
     * Executes the teardown based on the specified mode.
     */
    private fun executeTeardown(mode: TeardownMode): TeardownResult =
        when (mode) {
            is TeardownMode.CurrentCluster -> teardownCurrentCluster()
            is TeardownMode.SpecificVpc -> teardownSpecificVpc(mode.vpcId)
            is TeardownMode.AllTagged -> teardownAllTagged()
            is TeardownMode.PackerInfrastructure -> teardownPackerInfrastructure()
        }

    /**
     * Tears down the current cluster using VPC ID from cluster state.
     */
    private fun teardownCurrentCluster(): TeardownResult {
        // Get the VPC ID from cluster state
        if (!clusterStateManager.exists()) {
            outputHandler.handleMessage("No cluster state found. Use --all to find tagged VPCs or specify a VPC ID.")
            return TeardownResult.failure("No cluster state found")
        }

        val clusterState = clusterStateManager.load()
        val currentVpcId = clusterState.vpcId

        if (currentVpcId == null) {
            outputHandler.handleMessage(
                "No VPC ID stored in cluster state for '${clusterState.name}'. " +
                    "Use --all to find tagged VPCs or specify a VPC ID.",
            )
            return TeardownResult.failure("No VPC ID in cluster state")
        }

        return teardownSpecificVpc(currentVpcId)
    }

    /**
     * Tears down a specific VPC by ID.
     */
    private fun teardownSpecificVpc(targetVpcId: String): TeardownResult {
        outputHandler.handleMessage("Preparing to tear down VPC: $targetVpcId")

        // Discover resources first
        val resources = teardownService.discoverResources(targetVpcId)

        if (dryRun) {
            outputHandler.handleMessage("\n=== DRY RUN - Resources that would be deleted ===")
            outputHandler.handleMessage(resources.summary())
            outputHandler.handleMessage("=== End DRY RUN ===\n")
            return TeardownResult.success(resources)
        }

        // Confirm if not auto-approved
        if (!autoApprove && !confirmTeardown(resources.summary())) {
            outputHandler.handleMessage("Teardown cancelled by user")
            return TeardownResult.failure("Teardown cancelled by user")
        }

        return teardownService.teardownVpc(targetVpcId, dryRun = false)
    }

    /**
     * Tears down all VPCs tagged with easy_cass_lab.
     */
    private fun teardownAllTagged(): TeardownResult {
        outputHandler.handleMessage("Finding all VPCs tagged with easy_cass_lab...")

        // Preview first
        val previewResult = teardownService.teardownAllTagged(dryRun = true, includePackerVpc = teardownPacker)

        if (previewResult.resourcesDeleted.isEmpty()) {
            outputHandler.handleMessage("No tagged VPCs found to tear down")
            return previewResult
        }

        if (dryRun) {
            return previewResult
        }

        // Build summary for confirmation
        val summary =
            buildString {
                appendLine("Resources to be deleted:")
                previewResult.resourcesDeleted.forEach { resources ->
                    appendLine(resources.summary())
                    appendLine()
                }
            }

        // Confirm if not auto-approved
        if (!autoApprove && !confirmTeardown(summary)) {
            outputHandler.handleMessage("Teardown cancelled by user")
            return TeardownResult.failure("Teardown cancelled by user")
        }

        return teardownService.teardownAllTagged(dryRun = false, includePackerVpc = teardownPacker)
    }

    /**
     * Tears down the packer infrastructure VPC.
     */
    private fun teardownPackerInfrastructure(): TeardownResult {
        outputHandler.handleMessage("Finding packer infrastructure VPC...")

        // Preview first
        val previewResult = teardownService.teardownPackerInfrastructure(dryRun = true)

        if (previewResult.resourcesDeleted.isEmpty()) {
            outputHandler.handleMessage("No packer VPC found")
            return previewResult
        }

        if (dryRun) {
            return previewResult
        }

        // Build summary for confirmation
        val summary = previewResult.resourcesDeleted.first().summary()

        // Confirm if not auto-approved
        if (!autoApprove && !confirmTeardown(summary)) {
            outputHandler.handleMessage("Teardown cancelled by user")
            return TeardownResult.failure("Teardown cancelled by user")
        }

        return teardownService.teardownPackerInfrastructure(dryRun = false)
    }

    /**
     * Prompts the user to confirm the teardown operation.
     *
     * @param summary Summary of resources to be deleted
     * @return True if user confirms, false otherwise
     */
    private fun confirmTeardown(summary: String): Boolean {
        outputHandler.handleMessage("\n=== Resources to be deleted ===")
        outputHandler.handleMessage(summary)
        outputHandler.handleMessage("================================\n")
        outputHandler.handleMessage("Are you sure you want to delete these resources? (yes/no)")

        val scanner = Scanner(System.`in`)
        val response = scanner.nextLine().trim().lowercase()

        return response == "yes" || response == "y"
    }

    /**
     * Reports the result of the teardown operation.
     */
    private fun reportResult(result: TeardownResult) {
        if (result.success) {
            outputHandler.handleMessage("\nTeardown completed successfully")
        } else {
            outputHandler.handleMessage("\nTeardown completed with errors:")
            result.errors.forEach { error ->
                outputHandler.handleMessage("  - $error")
            }
        }
    }

    /**
     * Cleanup SOCKS5 proxy if it exists.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun cleanupSocks5Proxy() {
        val proxyStateFile = File(".socks5-proxy-state")
        if (!proxyStateFile.exists()) {
            return
        }

        try {
            val mapper = jacksonObjectMapper()
            val proxyState = mapper.readValue<Socks5ProxyState>(proxyStateFile)

            // Try to kill the process
            try {
                val process = ProcessBuilder("kill", proxyState.pid.toString()).start()
                process.waitFor()
                if (process.exitValue() == 0) {
                    outputHandler.handleMessage("Stopped SOCKS5 proxy (PID: ${proxyState.pid})")
                } else {
                    log.warn { "Failed to kill SOCKS5 proxy process ${proxyState.pid}, it may already be stopped" }
                }
            } catch (e: Exception) {
                log.warn(e) { "Error killing SOCKS5 proxy process ${proxyState.pid}" }
            }

            // Remove the state file
            proxyStateFile.delete()
        } catch (e: Exception) {
            log.warn(e) { "Failed to read or cleanup SOCKS5 proxy state, continuing anyway" }
            // Try to delete the file anyway
            proxyStateFile.delete()
        }
    }

    /**
     * Mark infrastructure as DOWN in cluster state.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun updateClusterState() {
        try {
            if (clusterStateManager.exists()) {
                val clusterState = clusterStateManager.load()
                clusterState.markInfrastructureDown()
                clusterStateManager.save(clusterState)
                outputHandler.handleMessage("Cluster state updated: infrastructure marked as DOWN")
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to update cluster state, continuing anyway" }
        }
    }
}
