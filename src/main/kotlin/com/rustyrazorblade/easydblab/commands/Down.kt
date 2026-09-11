package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredResources
import com.rustyrazorblade.easydblab.providers.aws.TeardownMode
import com.rustyrazorblade.easydblab.providers.aws.TeardownResult
import com.rustyrazorblade.easydblab.proxy.Socks5ProxyStateFile
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.services.TailscaleService
import com.rustyrazorblade.easydblab.services.TeardownBackupService
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import org.koin.core.component.inject
import picocli.CommandLine
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
@CommandLine.Command(
    name = "down",
    description = ["Shut down AWS infrastructure"],
)
@Suppress("TooManyFunctions")
class Down : PicoBaseCommand() {
    @CommandLine.Parameters(
        index = "0",
        arity = "0..1",
        description = ["Optional VPC ID to tear down a specific VPC"],
    )
    var vpcId: String? = null

    @CommandLine.Option(
        names = ["--all"],
        description = ["Tear down all VPCs tagged with easy_cass_lab"],
    )
    var teardownAll = false

    @CommandLine.Option(
        names = ["--packer"],
        description = ["Tear down the packer infrastructure VPC"],
    )
    var teardownPacker = false

    @CommandLine.Option(
        names = ["--dry-run"],
        description = ["Preview what would be deleted without actually deleting"],
    )
    var dryRun = false

    @CommandLine.Option(
        names = ["--auto-approve", "-a", "--yes"],
        description = ["Auto approve changes without confirmation prompt"],
    )
    var autoApprove = false

    @CommandLine.Option(
        names = ["--retention-days"],
        description = ["Days to retain S3 data after teardown (default: 1)"],
        defaultValue = "1",
    )
    var retentionDays: Int = 1

    @CommandLine.Option(
        names = ["--force"],
        description = ["Skip the pre-teardown metrics + annotations backup and tear down anyway"],
    )
    var force = false

    private val teardownService: AwsInfrastructureService by inject()
    private val s3BucketService: AwsS3BucketService by inject()
    private val tailscaleService: TailscaleService by inject()
    private val user: User by inject()
    private val teardownBackupService: TeardownBackupService by inject()
    private val socksProxyService: SocksProxyService by inject()
    private val log = KotlinLogging.logger {}

    override fun execute() {
        val mode = determineTeardownMode()

        eventBus.emit(Event.Teardown.Starting)

        // Back up metrics and annotations FIRST, before any infrastructure is touched. If the
        // backup fails, abort with no infrastructure removed so the data is not lost to teardown.
        // Runs before the proxy is torn down; --force skips it. See design decision D3.
        if (!backupBeforeTeardown(mode)) {
            return
        }

        // Clear JVM SOCKS proxy settings before teardown so all AWS SDK calls go directly
        // to public AWS endpoints. The control node (and its SSH tunnel) will be terminated
        // during teardown, which would break mid-flight proxy connections. AWS API calls
        // never need the proxy — only private cluster network access does.
        clearProxySystemProperties()

        val result = executeTeardown(mode)

        // Kill the proxy process and remove its state file after AWS operations complete.
        cleanupSocks5Proxy()

        // Only clear cluster state on successful teardown to preserve VPC ID for retries
        if (result.success && (mode == TeardownMode.CurrentCluster || mode is TeardownMode.SpecificVpc)) {
            updateClusterState()
        }

        // Report results
        reportResult(result)
    }

    /**
     * Runs the coupled metrics + annotations backup before any teardown, and decides whether the
     * teardown may proceed.
     *
     * The backup only applies to the current-cluster teardown of a running cluster: the other modes
     * (`--all`, `--packer`, a specific VPC id, `--dry-run`) do not map to a single reachable control
     * node, and `--force` skips it outright. When the backup runs and fails, the teardown aborts with
     * no infrastructure removed. See design decisions D3 and D4.
     *
     * @return true to proceed with teardown, false to abort with nothing removed.
     */
    private fun backupBeforeTeardown(mode: TeardownMode): Boolean {
        if (force || dryRun || mode != TeardownMode.CurrentCluster || !clusterStateManager.exists()) {
            return true
        }

        val state = clusterStateManager.load()
        if (!state.isInfrastructureUp()) {
            eventBus.emit(Event.Teardown.BackupSkipped("cluster infrastructure is not up"))
            return true
        }

        val controlHost = state.getControlHost()
        if (controlHost == null) {
            eventBus.emit(Event.Teardown.BackupSkipped("no control node found in cluster state"))
            return true
        }

        eventBus.emit(Event.Teardown.BackupStarting)
        // The tunnel setup and the backup are one failure boundary: a tunnel failure is a backup
        // failure. Both are inside the runCatching so either aborts teardown with the standard
        // "no infrastructure removed / pass --force" guidance rather than a raw stack trace. The
        // tunnel is established here, before clearProxySystemProperties()/cleanupSocks5Proxy() tear
        // it down.
        return runCatching {
            socksProxyService.ensureRunning(controlHost)
            teardownBackupService.backupBeforeTeardown(controlHost, state).getOrThrow()
        }.fold(
            onSuccess = { true },
            onFailure = { failure ->
                eventBus.emit(Event.Teardown.BackupFailedAbort(failure.message ?: "unknown error"))
                false
            },
        )
    }

    /**
     * Determines the teardown mode based on command line options.
     */
    private fun determineTeardownMode(): TeardownMode =
        when {
            vpcId != null -> TeardownMode.SpecificVpc(requireNotNull(vpcId))
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
            eventBus.emit(Event.Teardown.NoClusterState)
            return TeardownResult.Companion.failure("No cluster state found")
        }

        val clusterState = clusterStateManager.load()
        val currentVpcId = clusterState.vpcId

        if (currentVpcId == null) {
            eventBus.emit(Event.Teardown.NoVpcId(clusterState.name))
            return TeardownResult.Companion.failure("No VPC ID in cluster state")
        }

        return teardownSpecificVpc(currentVpcId)
    }

    /**
     * Tears down a specific VPC by ID.
     */
    private fun teardownSpecificVpc(targetVpcId: String): TeardownResult {
        eventBus.emit(Event.Teardown.PreparingVpc(targetVpcId))

        // Preview to discover resources
        val previewResult = teardownService.teardownVpc(targetVpcId, dryRun = true)

        if (previewResult.resourcesDeleted.isEmpty()) {
            eventBus.emit(Event.Teardown.NoResourcesFound)
            return previewResult
        }

        val summary = previewResult.resourcesDeleted.first().summary()

        if (dryRun) {
            eventBus.emit(Event.Teardown.DryRunPreview(summary))
            return previewResult
        }

        // Confirm if not auto-approved
        if (!autoApprove && !confirmTeardown(summary)) {
            eventBus.emit(Event.Teardown.CancelledByUser)
            return TeardownResult.Companion.failure("Teardown cancelled by user")
        }

        return teardownService.teardownVpc(targetVpcId, dryRun = false)
    }

    /**
     * Tears down all VPCs tagged with easy_cass_lab.
     */
    private fun teardownAllTagged(): TeardownResult {
        eventBus.emit(Event.Teardown.FindingTaggedVpcs)

        // Preview first
        val previewResult = teardownService.teardownAllTagged(dryRun = true, includePackerVpc = teardownPacker)

        if (previewResult.resourcesDeleted.isEmpty()) {
            eventBus.emit(Event.Teardown.NoTaggedVpcsFound)
            return previewResult
        }

        // Build summary from discovered resources
        val summary = buildResourcesSummary(previewResult.resourcesDeleted)

        if (dryRun) {
            eventBus.emit(Event.Teardown.DryRunPreview(summary))
            return previewResult
        }

        // Confirm if not auto-approved
        if (!autoApprove && !confirmTeardown(summary)) {
            eventBus.emit(Event.Teardown.CancelledByUser)
            return TeardownResult.Companion.failure("Teardown cancelled by user")
        }

        val result = teardownService.teardownAllTagged(dryRun = false, includePackerVpc = teardownPacker)

        // Also handle all data buckets when tearing down all resources
        if (result.success) {
            teardownAllDataBuckets()
        }

        return result
    }

    /**
     * Tears down the packer infrastructure VPC.
     */
    private fun teardownPackerInfrastructure(): TeardownResult {
        eventBus.emit(Event.Teardown.FindingPackerVpc)

        // Preview first
        val previewResult = teardownService.teardownPackerInfrastructure(dryRun = true)

        if (previewResult.resourcesDeleted.isEmpty()) {
            eventBus.emit(Event.Teardown.NoPackerVpcFound)
            return previewResult
        }

        val summary = previewResult.resourcesDeleted.first().summary()

        if (dryRun) {
            eventBus.emit(Event.Teardown.DryRunPreview(summary))
            return previewResult
        }

        // Confirm if not auto-approved
        if (!autoApprove && !confirmTeardown(summary)) {
            eventBus.emit(Event.Teardown.CancelledByUser)
            return TeardownResult.Companion.failure("Teardown cancelled by user")
        }

        return teardownService.teardownPackerInfrastructure(dryRun = false)
    }

    /**
     * Builds a summary string from multiple discovered resources.
     *
     * @param resources List of discovered resources to summarize
     * @return Combined summary string
     */
    private fun buildResourcesSummary(resources: List<DiscoveredResources>): String =
        buildString {
            resources.forEachIndexed { index, resource ->
                append(resource.summary())
                if (index < resources.size - 1) {
                    appendLine()
                }
            }
        }

    /**
     * Prompts the user to confirm the teardown operation.
     *
     * @param summary Summary of resources to be deleted
     * @return True if user confirms, false otherwise
     */
    private fun confirmTeardown(summary: String): Boolean {
        eventBus.emit(Event.Teardown.ConfirmationPrompt(summary))

        return Scanner(System.`in`).use { scanner ->
            val response = scanner.nextLine().trim().lowercase()
            response == "yes" || response == "y"
        }
    }

    /**
     * Reports the result of the teardown operation.
     */
    private fun reportResult(result: TeardownResult) {
        if (result.success) {
            eventBus.emit(Event.Teardown.CompletedSuccessfully)
        } else {
            eventBus.emit(Event.Teardown.CompletedWithErrors(result.errors))
        }
    }

    /**
     * Unpublishes the SOCKS proxy port so the cluster clients stop routing through the tunnel before
     * it is torn down. AWS SDK calls already go direct (the proxy is never applied to them), so this
     * only affects cluster-internal clients, which teardown no longer needs.
     */
    internal fun clearProxySystemProperties() {
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
        log.debug { "Cleared published SOCKS proxy port property for teardown" }
    }

    /**
     * Cleanup SOCKS5 proxy if it exists.
     *
     * Resolves the state file against [Context.workingDirectory] — the same location
     * [com.rustyrazorblade.easydblab.proxy.ProcessSocksProxyService] writes it to. Resolving it
     * against the process cwd instead would miss the file whenever `workingDirectory` is set
     * explicitly rather than inherited from cwd (long-running `Server`/`Repl`, tests), orphaning
     * the `ssh -N -D` tunnel process at teardown.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun cleanupSocks5Proxy() {
        val proxyStateFile = File(context.workingDirectory, Constants.Vpc.SOCKS5_PROXY_STATE_FILE)
        if (!proxyStateFile.exists()) {
            return
        }

        try {
            val proxyState = Json.decodeFromString<Socks5ProxyStateFile>(proxyStateFile.readText())

            // Try to kill the process
            try {
                val process = ProcessBuilder("kill", proxyState.pid.toString()).start()
                process.waitFor()
                if (process.exitValue() == 0) {
                    eventBus.emit(Event.Teardown.Socks5ProxyStopped(proxyState.pid))
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
     * Mark infrastructure as DOWN and clear resource data in cluster state.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun updateClusterState() {
        try {
            if (clusterStateManager.exists()) {
                val clusterState = clusterStateManager.load()

                // Delete Tailscale auth key if it exists
                deleteTailscaleAuthKey(clusterState)

                // Set lifecycle expiration rule on cluster prefix in account bucket
                setClusterLifecycleRule(clusterState)

                // Disable metrics and set lifecycle expiration on the data bucket
                teardownDataBucketIfNeeded(clusterState)

                clusterState.markInfrastructureDown()
                clusterState.updateHosts(emptyMap())
                clusterState.updateEmrCluster(null)
                clusterState.updateOpenSearchDomain(null)
                clusterState.updateInfrastructure(null)
                clusterState.updateTailscaleAuthKeyId(null)
                clusterStateManager.save(clusterState)
                eventBus.emit(Event.Teardown.ClusterStateMarkedDown)
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to update cluster state, continuing anyway" }
        }
    }

    /**
     * Deletes the Tailscale auth key if one is stored in cluster state.
     * Non-fatal — failure to delete the key should not block teardown.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun deleteTailscaleAuthKey(clusterState: ClusterState) {
        val keyId = clusterState.tailscaleAuthKeyId
        if (keyId.isNullOrBlank()) {
            log.debug { "No Tailscale auth key to delete" }
            return
        }

        val clientId = user.tailscaleClientId
        val clientSecret = user.tailscaleClientSecret
        if (clientId.isBlank() || clientSecret.isBlank()) {
            log.warn { "Tailscale OAuth credentials not configured, cannot delete auth key $keyId" }
            return
        }

        try {
            tailscaleService.deleteAuthKey(clientId, clientSecret, keyId)
            eventBus.emit(Event.Tailscale.AuthKeyDeleted(keyId))
        } catch (e: Exception) {
            log.warn(e) { "Failed to delete Tailscale auth key: $keyId" }
        }
    }

    /**
     * Sets an S3 lifecycle expiration rule on the cluster's prefix.
     * This schedules all objects under the cluster prefix for deletion after retentionDays.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun setClusterLifecycleRule(clusterState: ClusterState) {
        val bucketName = clusterState.s3Bucket
        if (bucketName.isNullOrBlank()) {
            log.debug { "No S3 bucket configured, skipping lifecycle rule" }
            return
        }

        try {
            val clusterPrefix = clusterState.clusterPrefix() + "/"
            s3BucketService.setLifecycleExpirationRule(bucketName, clusterPrefix, retentionDays)
            eventBus.emit(Event.S3.LifecycleRuleSet(clusterPrefix, retentionDays))
        } catch (e: Exception) {
            log.warn(e) { "Failed to set S3 lifecycle rule" }
        }
    }

    /**
     * Disables metrics and sets lifecycle expiration on the per-cluster data bucket.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun teardownDataBucketIfNeeded(clusterState: ClusterState) {
        val dataBucket = clusterState.dataBucket
        if (dataBucket.isBlank()) {
            log.debug { "No data bucket configured, skipping teardown" }
            return
        }

        try {
            s3BucketService.teardownDataBucket(dataBucket, clusterState.metricsConfigId(), retentionDays)
        } catch (e: Exception) {
            log.warn(e) { "Failed to tear down data bucket: $dataBucket" }
        }
    }

    /**
     * Finds all data buckets and sets lifecycle expiration on each, then attempts deletion.
     * Called during --all teardown.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun teardownAllDataBuckets() {
        try {
            val dataBuckets = s3BucketService.findDataBuckets()
            if (dataBuckets.isEmpty()) {
                log.debug { "No data buckets found" }
                return
            }

            for (bucket in dataBuckets) {
                teardownSingleDataBucket(bucket)
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to discover data buckets" }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun teardownSingleDataBucket(bucket: String) {
        try {
            s3BucketService.setFullBucketLifecycleExpiration(bucket, retentionDays)
            eventBus.emit(Event.S3.DataBucketExpiring(bucket, retentionDays))

            eventBus.emit(Event.S3.DataBucketDeleting(bucket))
            if (s3BucketService.deleteEmptyBucket(bucket)) {
                eventBus.emit(Event.S3.DataBucketDeleted(bucket))
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to handle data bucket: $bucket" }
        }
    }
}
