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
import com.rustyrazorblade.easydblab.services.BackendState
import com.rustyrazorblade.easydblab.services.FlushStepFailed
import com.rustyrazorblade.easydblab.services.TailscaleApiException
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
        names = ["--force"],
        description = ["Skip the pre-teardown annotation mirror, Loki and Mimir flushes and annotations backup, and tear down anyway"],
    )
    var force = false

    private val teardownService: AwsInfrastructureService by inject()
    private val s3BucketService: AwsS3BucketService by inject()
    private val tailscaleService: TailscaleService by inject()
    private val user: User by inject()
    private val teardownBackupService: TeardownBackupService by inject()
    private val socksProxyService: SocksProxyService by inject()
    private val log = KotlinLogging.logger {}

    /**
     * The process exit code. Set to [Constants.ExitCodes.ERROR] when the pre-teardown backup aborts
     * the teardown, when the teardown completes with errors, or when the user declines the
     * confirmation prompt, so a caller scripting `down` never mistakes those for success.
     */
    private var exitCode = 0

    override fun call(): Int {
        super.call()
        return exitCode
    }

    /**
     * How a teardown ended: it ran (successfully or not, or it had nothing to do), or the
     * pre-teardown flush failed and aborted it with no infrastructure removed.
     */
    private sealed interface TeardownOutcome {
        data class Finished(
            val result: TeardownResult,
        ) : TeardownOutcome

        data object FlushAborted : TeardownOutcome
    }

    override fun execute() {
        val mode = determineTeardownMode()

        eventBus.emit(Event.Teardown.Starting)

        var result =
            when (val outcome = executeTeardown(mode)) {
                TeardownOutcome.FlushAborted -> {
                    exitCode = Constants.ExitCodes.ERROR
                    return
                }
                is TeardownOutcome.Finished -> outcome.result
            }

        // Kill the proxy process and remove its state file after AWS operations complete.
        cleanupSocks5Proxy()

        // Only clear cluster state on successful teardown to preserve VPC ID for retries
        if (result.success && (mode == TeardownMode.CurrentCluster || mode is TeardownMode.SpecificVpc)) {
            result = removeTailscaleDevice(result)
            updateClusterState()
        }

        // Report results
        reportResult(result)
    }

    /**
     * Saves the cluster's tail (annotation mirror, Loki and Mimir flushes, annotations backup) once
     * the current-cluster teardown is certain to go ahead: its preview found resources and the
     * operator confirmed. The flush leaves Loki and Mimir stopped, so it must not run for a teardown
     * that is then declined. See issue 967, decision D2.
     *
     * Only the current-cluster teardown of a running cluster saves its tail: the other modes
     * (`--all`, `--packer`, a specific VPC id) do not map to a single reachable control node, and
     * `--force` skips it outright. A flush an earlier `down` completed is not run again: Loki and
     * Mimir are already at 0 and hold nothing new. When the flush fails it stops where it is —
     * nothing is undone and no backend is started again (owner decision, 2026-09-26) — and the
     * teardown does not run.
     *
     * @return whether the teardown may go ahead.
     */
    private fun saveTailBeforeTeardown(): Boolean {
        if (force || !clusterStateManager.exists()) {
            return true
        }

        val state = clusterStateManager.load()
        if (!state.isInfrastructureUp()) {
            eventBus.emit(Event.Teardown.BackupSkipped("cluster infrastructure is not up"))
            return true
        }

        // A redirect cluster runs no local Mimir, Loki or Grafana, so there is nothing to flush or
        // back up here; the data already lives on the external stack.
        val redirect = state.initConfig?.telemetryRedirect
        if (redirect != null) {
            eventBus.emit(
                Event.Teardown.BackupSkipped(
                    "telemetry is redirected to ${redirect.metrics}; there is no local stack to flush",
                ),
            )
            return true
        }

        // Checked before the tunnel: after a teardown that failed part-way the control node may
        // already be gone, and the backends it ran hold nothing the recorded flush missed.
        val flushed = state.tailFlush
        if (flushed != null) {
            eventBus.emit(
                Event.Teardown.TailAlreadyFlushed(
                    completedAt = flushed.completedAt.toString(),
                    lokiIndexFiles = flushed.lokiIndexFiles,
                    lokiChunksFlushed = flushed.lokiChunksFlushed,
                    mimirBlocks = flushed.mimirBlocks,
                ),
            )
            return true
        }

        val controlHost = state.getControlHost()
        if (controlHost == null) {
            eventBus.emit(Event.Teardown.BackupSkipped("no control node found in cluster state"))
            return true
        }

        eventBus.emit(Event.Teardown.BackupStarting)
        // The tunnel setup and the backup are one failure boundary: a tunnel failure is a backup
        // failure. Both are inside the runCatching so either stops `down` with the standard
        // "no infrastructure removed / --force" guidance rather than a raw stack trace. The
        // tunnel is established here, before clearProxySystemProperties()/cleanupSocks5Proxy() tear
        // it down.
        return runCatching {
            socksProxyService.ensureRunning(controlHost)
            teardownBackupService.backupBeforeTeardown(controlHost, state).getOrThrow()
        }.fold(
            onSuccess = { true },
            onFailure = { failure ->
                eventBus.emit(abortEvent(failure))
                false
            },
        )
    }

    /**
     * The report of a flush that stopped. A [FlushStepFailed] names its step and the backends as it
     * left them; any other failure is the tunnel's, which touched no backend.
     */
    private fun abortEvent(failure: Throwable): Event.Teardown.BackupFailedAbort =
        when (failure) {
            is FlushStepFailed ->
                Event.Teardown.BackupFailedAbort(
                    step = failure.step.description,
                    reason = failure.cause?.message ?: failure.message.orEmpty(),
                    backends = failure.backends.mapValues { it.value.description },
                    stoppedBackends =
                        failure.backends
                            .filterValues { it != BackendState.RUNNING }
                            .keys
                            .toList(),
                )
            else ->
                Event.Teardown.BackupFailedAbort(
                    step = "SOCKS tunnel to the control node",
                    reason = failure.message ?: failure.toString(),
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
    private fun executeTeardown(mode: TeardownMode): TeardownOutcome =
        when (mode) {
            is TeardownMode.CurrentCluster -> teardownCurrentCluster()
            is TeardownMode.SpecificVpc -> teardownSpecificVpc(mode.vpcId, saveTail = false)
            is TeardownMode.AllTagged -> TeardownOutcome.Finished(teardownAllTagged())
            is TeardownMode.PackerInfrastructure -> TeardownOutcome.Finished(teardownPackerInfrastructure())
        }

    /**
     * Unpublishes the SOCKS proxy port just before infrastructure is removed. The control node (and
     * its SSH tunnel) is terminated during teardown, which would break mid-flight proxy connections;
     * AWS API calls never need the proxy, only private cluster network access does.
     */
    private fun beforeRemovingInfrastructure() = clearProxySystemProperties()

    /**
     * Tears down the current cluster using VPC ID from cluster state.
     *
     * A cluster whose VPC is already gone but whose tailnet device is still recorded is a previous
     * `down` that removed the infrastructure and failed only the device removal. There is no VPC
     * left to tear down, so this succeeds with nothing deleted and lets the device removal retry.
     */
    private fun teardownCurrentCluster(): TeardownOutcome {
        // Get the VPC ID from cluster state
        if (!clusterStateManager.exists()) {
            eventBus.emit(Event.Teardown.NoClusterState)
            return TeardownOutcome.Finished(TeardownResult.Companion.failure("No cluster state found"))
        }

        val clusterState = clusterStateManager.load()
        val currentVpcId = clusterState.vpcId

        if (currentVpcId == null && !clusterState.tailscaleDeviceId.isNullOrBlank()) {
            return TeardownOutcome.Finished(TeardownResult.success(emptyList()))
        }

        if (currentVpcId == null) {
            eventBus.emit(Event.Teardown.NoVpcId(clusterState.name))
            return TeardownOutcome.Finished(TeardownResult.Companion.failure("No VPC ID in cluster state"))
        }

        return teardownSpecificVpc(currentVpcId, saveTail = true)
    }

    /**
     * Tears down a specific VPC by ID.
     *
     * The preview, the dry run and the confirmation all come first. Only once the teardown is
     * certain to go ahead does [saveTail] run the pre-teardown flush, and only once that succeeded
     * is any infrastructure removed. A removal that fails after the flush restores nothing: the
     * owner wants the cluster down, so Loki and Mimir stay at 0 and the failure is reported.
     */
    private fun teardownSpecificVpc(
        targetVpcId: String,
        saveTail: Boolean,
    ): TeardownOutcome {
        eventBus.emit(Event.Teardown.PreparingVpc(targetVpcId))

        // Preview to discover resources
        val previewResult = teardownService.teardownVpc(targetVpcId, dryRun = true)

        if (previewResult.resourcesDeleted.isEmpty()) {
            eventBus.emit(Event.Teardown.NoResourcesFound)
            return TeardownOutcome.Finished(previewResult)
        }

        val summary = previewResult.resourcesDeleted.first().summary()

        if (dryRun) {
            eventBus.emit(Event.Teardown.DryRunPreview(summary))
            return TeardownOutcome.Finished(previewResult)
        }

        // Confirm if not auto-approved
        if (!autoApprove && !confirmTeardown(summary)) {
            eventBus.emit(Event.Teardown.CancelledByUser)
            return TeardownOutcome.Finished(TeardownResult.Companion.failure("Teardown cancelled by user"))
        }

        if (saveTail && !saveTailBeforeTeardown()) return TeardownOutcome.FlushAborted

        beforeRemovingInfrastructure()
        return TeardownOutcome.Finished(teardownService.teardownVpc(targetVpcId, dryRun = false))
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

        beforeRemovingInfrastructure()
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

        beforeRemovingInfrastructure()
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
     * Reports the result of the teardown operation and sets the exit code from it, so a teardown
     * that failed or that the user declined at the prompt exits non-zero.
     */
    private fun reportResult(result: TeardownResult) {
        if (result.success) {
            eventBus.emit(Event.Teardown.CompletedSuccessfully)
        } else {
            eventBus.emit(Event.Teardown.CompletedWithErrors(result.errors))
            exitCode = Constants.ExitCodes.ERROR
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

                // Stop the data bucket's request metrics. The bucket and its objects are the
                // owner's data and are never expired or deleted here.
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
     * Removes this cluster's control node from the tailnet, by the device ID `tailscale start`
     * recorded. The instance is gone, but its tailnet device outlives it, and every cluster's
     * control node registers under the same hostname, so the recorded ID is the only precise
     * handle on it.
     *
     * Unlike the auth key, a device left behind is visible clutter that keeps advertising this
     * cluster's subnet route, so a failure here fails `down` (non-zero exit, the reason listed
     * with the teardown errors) and the ID stays in state for the next `down` to retry. The rest
     * of the cluster state is still cleared; [teardownCurrentCluster] retries on the recorded ID
     * alone, without a VPC ID.
     *
     * @return [result] unchanged when there was nothing to remove or it was removed; otherwise a
     *   failed result carrying the reason.
     */
    private fun removeTailscaleDevice(result: TeardownResult): TeardownResult {
        if (!clusterStateManager.exists()) return result
        val clusterState = clusterStateManager.load()
        val deviceId = clusterState.tailscaleDeviceId
        if (deviceId.isNullOrBlank()) return result

        val clientId = user.tailscaleClientId
        val clientSecret = user.tailscaleClientSecret
        val failure =
            if (clientId.isBlank() || clientSecret.isBlank()) {
                "Tailscale device $deviceId (the control node) was not removed from the tailnet: " +
                    "no Tailscale OAuth credentials are configured. Configure them with " +
                    "'easy-db-lab profile setup' and run 'easy-db-lab down' again, or remove the device at " +
                    "https://login.tailscale.com/admin/machines."
            } else {
                try {
                    tailscaleService.deleteDevice(clientId, clientSecret, deviceId)
                    null
                } catch (e: TailscaleApiException) {
                    "Tailscale device $deviceId (the control node) was not removed from the tailnet: ${e.message}"
                }
            }

        if (failure != null) {
            return result.copy(success = false, errors = result.errors + failure)
        }
        eventBus.emit(Event.Tailscale.DeviceDeleted(deviceId))
        clusterState.tailscaleDeviceId = null
        clusterStateManager.save(clusterState)
        return result
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
     * Disables request metrics on the per-cluster data bucket. Sets no expiry: the bucket's objects
     * are the owner's data.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun teardownDataBucketIfNeeded(clusterState: ClusterState) {
        val dataBucket = clusterState.dataBucket
        if (dataBucket.isBlank()) {
            log.debug { "No data bucket configured, skipping teardown" }
            return
        }

        try {
            s3BucketService.teardownDataBucket(dataBucket, clusterState.metricsConfigId())
        } catch (e: Exception) {
            log.warn(e) { "Failed to tear down data bucket: $dataBucket" }
        }
    }

    /**
     * Finds all data buckets and deletes each one that is empty. A bucket that still holds objects
     * is kept as it is, with no expiry rule. Called during --all teardown.
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
            eventBus.emit(Event.S3.DataBucketDeleting(bucket))
            s3BucketService
                .deleteEmptyBucket(bucket)
                .onSuccess { eventBus.emit(Event.S3.DataBucketDeleted(bucket)) }
                .onFailure { exception ->
                    eventBus.emit(Event.S3.DataBucketKept(bucket, exception.message ?: exception.toString()))
                }
        } catch (e: Exception) {
            log.warn(e) { "Failed to handle data bucket: $bucket" }
        }
    }
}
