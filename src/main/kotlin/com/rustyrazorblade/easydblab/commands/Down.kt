package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.TailFlushRecord
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredResources
import com.rustyrazorblade.easydblab.providers.aws.TeardownMode
import com.rustyrazorblade.easydblab.providers.aws.TeardownResult
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.services.BackendState
import com.rustyrazorblade.easydblab.services.TailFlushFailed
import com.rustyrazorblade.easydblab.services.TailSignal
import com.rustyrazorblade.easydblab.services.TailscaleApiException
import com.rustyrazorblade.easydblab.services.TailscaleService
import com.rustyrazorblade.easydblab.services.TeardownBackupService
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService
import com.rustyrazorblade.easydblab.services.aws.CompactorService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine
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
        description = [
            "Skip the pre-teardown save of logs, metrics, traces and annotations, and tear down anyway. " +
                "The signals not yet in S3 are listed before the confirmation prompt.",
        ],
    )
    var force = false

    private val teardownService: AwsInfrastructureService by inject()
    private val s3BucketService: AwsS3BucketService by inject()
    private val tailscaleService: TailscaleService by inject()
    private val user: User by inject()
    private val teardownBackupService: TeardownBackupService by inject()
    private val socksProxyService: SocksProxyService by inject()
    private val compactorService: CompactorService by inject()
    private val log = KotlinLogging.logger {}

    private companion object {
        /** The step a tunnel failure names: it fails every signal before the save starts. */
        const val TUNNEL_STEP = "SOCKS tunnel to the control node"

        /** The step a failure of the save itself names, as opposed to one of its signals. */
        const val SAVE_STEP = "pre-teardown save"
    }

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

        if (result.success && !dryRun && mode != TeardownMode.PackerInfrastructure) {
            stopCompactorIfLastCluster(mode, result)
        }

        // Only clear cluster state on successful teardown to preserve VPC ID for retries
        if (result.success && (mode == TeardownMode.CurrentCluster || mode is TeardownMode.SpecificVpc)) {
            result = removeTailscaleDevice(result)
            updateClusterState()
        }

        // Report results
        reportResult(result)
    }

    /**
     * Saves the cluster's tail once the current-cluster teardown is certain to go ahead: its preview
     * found resources and the operator confirmed. The save leaves Loki and Mimir stopped and the OTel
     * collector deleted, so it must not run for a teardown that is then declined.
     *
     * Only the current-cluster teardown of a running cluster saves its tail: the other modes
     * (`--all`, `--packer`, a specific VPC id) do not map to a single reachable control node, and
     * `--force` skips it outright. A save an earlier `down` completed is skipped whole, before any
     * tunnel is opened: that `down`'s teardown then failed part-way, and the control node may be
     * gone. Otherwise logs and metrics an earlier `down` saved are skipped: Loki and Mimir are
     * already at 0 and hold nothing new, and the collector stop, the Tempo drain, the profiles report
     * and the annotations backup run again. When any signal fails, every other step still finishes,
     * nothing is undone and nothing is started again (owner decision, 2026-09-26), and the teardown
     * does not run.
     *
     * @return whether the teardown may go ahead.
     */
    private fun saveTailBeforeTeardown(): Boolean {
        val state = stateToSave() ?: return true

        val record = state.tailFlush ?: TailFlushRecord()
        val completedAt = record.saveCompletedAt
        // A complete save put every signal in S3; the ones it did not record were saved when it completed.
        val completeSave = completedAt?.let { at -> (TailSignal.entries - TailSignal.PROFILES).associateWith { at } }.orEmpty()
        val saved = completeSave + record.signals.mapValues { it.value.completedAt }
        if (saved.isNotEmpty()) {
            eventBus.emit(Event.Teardown.TailAlreadySaved(saved.entries.associate { (signal, at) -> signal.description to at.toString() }))
        }
        if (completedAt != null) return true

        val controlHost = state.getControlHost()
        if (controlHost == null) {
            eventBus.emit(Event.Teardown.BackupSkipped("no control node found in cluster state"))
            return true
        }

        eventBus.emit(Event.Teardown.BackupStarting)
        // The tunnel is established here, before clearProxySystemProperties()/cleanupSocks5Proxy() tear
        // it down. A tunnel failure touched nothing and fails every signal the save would have saved.
        // Either failure stops `down` with the standard "no infrastructure removed / --force"
        // guidance rather than a raw stack trace.
        runCatching { socksProxyService.ensureRunning(controlHost) }.onFailure { failure ->
            log.error(failure) { "The SOCKS tunnel to the control node failed before the pre-teardown save" }
            eventBus.emit(unsavedAbort(state, TUNNEL_STEP, failure))
            return false
        }
        return runCatching { teardownBackupService.backupBeforeTeardown(controlHost, state).getOrThrow() }.fold(
            onSuccess = { true },
            onFailure = { failure ->
                eventBus.emit(abortEvent(failure, state))
                false
            },
        )
    }

    /**
     * The cluster state whose tail the current-cluster teardown saves, or null when there is nothing
     * to save: no state, infrastructure not up, or telemetry redirected to an external stack. Each
     * skip says why.
     */
    private fun stateToSave(): ClusterState? {
        if (!clusterStateManager.exists()) return null
        val state = clusterStateManager.load()
        if (!state.isInfrastructureUp()) {
            eventBus.emit(Event.Teardown.BackupSkipped("cluster infrastructure is not up"))
            return null
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
            return null
        }
        return state
    }

    /**
     * Lists what `--force` will not save, with the teardown preview and before the confirmation
     * prompt, so the operator decides with it in view.
     */
    private fun reportForceSkipsTail() {
        val state = stateToSave() ?: return
        eventBus.emit(Event.Teardown.ForceSkipsTail(teardownBackupService.unsavedSignals(state).map { it.description }))
    }

    /**
     * The report of a save that failed. A [TailFlushFailed] names each failed signal, its step, and
     * the workloads as the save left them; any other failure is the save's own (writing the record,
     * for one), which fails every signal still unsaved.
     */
    private fun abortEvent(
        failure: Throwable,
        state: ClusterState,
    ): Event.Teardown.BackupFailedAbort =
        when (failure) {
            is TailFlushFailed ->
                Event.Teardown.BackupFailedAbort(
                    failures =
                        failure.failures.values.map {
                            Event.Teardown.BackupFailedAbort.SignalFailure(
                                signal = it.signal.description,
                                step = it.step.description,
                                reason = it.cause?.message ?: it.message.orEmpty(),
                            )
                        },
                    backends = failure.backends.mapValues { it.value.description },
                    stoppedWorkloads =
                        failure.backends
                            .filterValues { it != BackendState.RUNNING }
                            .keys
                            .toList(),
                )
            else -> {
                log.error(failure) { "The pre-teardown save failed" }
                unsavedAbort(state, SAVE_STEP, failure)
            }
        }

    /** A failure at [step] that fails every signal not yet saved. */
    private fun unsavedAbort(
        state: ClusterState,
        step: String,
        failure: Throwable,
    ) = Event.Teardown.BackupFailedAbort(
        failures =
            teardownBackupService.unsavedSignals(state).map {
                Event.Teardown.BackupFailedAbort.SignalFailure(
                    signal = it.description,
                    step = step,
                    reason = failure.message ?: failure.toString(),
                )
            },
    )

    /**
     * Stops the account compactor once the infrastructure teardown succeeded, when no other cluster
     * VPC in any region still names the account bucket. The VPCs this `down` tore down are left
     * out of the count. With no account bucket there is no compactor.
     */
    private fun stopCompactorIfLastCluster(
        mode: TeardownMode,
        result: TeardownResult,
    ) {
        val bucket =
            when (mode) {
                TeardownMode.CurrentCluster -> clusterStateManager.load().s3Bucket
                else -> user.s3Bucket
            }
        if (bucket.isNullOrBlank()) return
        compactorService.stopIfLastCluster(bucket, result.resourcesDeleted.map { it.vpcId }.toSet())
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
        if (saveTail && force) reportForceSkipsTail()

        if (dryRun) {
            eventBus.emit(Event.Teardown.DryRunPreview(summary))
            return TeardownOutcome.Finished(previewResult)
        }

        // Confirm if not auto-approved
        if (!autoApprove && !confirmTeardown(summary)) {
            eventBus.emit(Event.Teardown.CancelledByUser)
            return TeardownOutcome.Finished(TeardownResult.Companion.failure("Teardown cancelled by user"))
        }

        if (saveTail && !force && !saveTailBeforeTeardown()) return TeardownOutcome.FlushAborted

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
     * Stops the SOCKS5 tunnel through [SocksProxyService.stop], the same path `stop-socks` takes:
     * it ends the `ssh -N -D` process, deletes `.socks5-proxy-state` and removes the port from the
     * proxy env file, all in [Context.workingDirectory]. Resolving them against the process cwd
     * instead would miss them whenever `workingDirectory` is set explicitly (long-running
     * `Server`/`Repl`, tests), orphaning the tunnel process at teardown.
     */
    internal fun cleanupSocks5Proxy() {
        socksProxyService.stop()?.let { pid -> eventBus.emit(Event.Teardown.Socks5ProxyStopped(pid)) }
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
