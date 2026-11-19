package com.rustyrazorblade.easydblab.services.bringup.aws

import com.rustyrazorblade.easydblab.runCatchingInterruptibly
import com.rustyrazorblade.easydblab.providers.aws.InstanceId
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.InstanceStateReader
import com.rustyrazorblade.easydblab.services.bringup.InstanceAddresses
import com.rustyrazorblade.easydblab.services.bringup.InstanceTracker
import com.rustyrazorblade.easydblab.services.bringup.InstanceWatch
import com.rustyrazorblade.easydblab.services.bringup.Ticker
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * [InstanceTracker] on EC2: the one poller every node pipeline shares (design D9). Each poll makes
 * one `DescribeInstances` and one `DescribeInstanceStatus` request per
 * [InstanceStateReader.batchSize] watched instances, so 1,000 pipelines cost 20 requests a poll,
 * not 2,000.
 *
 * A watch's `running` completes once EC2 reports the instance running with a public IP, `statusOk`
 * once both status checks pass, and `ended` when EC2 reports it shutting down, terminated, stopping
 * or stopped, or stops returning an instance it had returned. An instance stays watched after it
 * is running and checked, so its end is seen in whatever step the pipeline is in, until the
 * pipeline unwatches it.
 *
 * A failed read is logged and skipped: the next poll reads again, and nothing is ended by it.
 */
class Ec2InstanceStateWatcher(
    private val reader: InstanceStateReader,
    private val interval: Duration = Duration.ofMillis(EC2InstanceService.POLL_INTERVAL_MS),
) : InstanceTracker {
    /** A watch, and whether EC2 has returned its instance yet. */
    private class Watched(
        val watch: InstanceWatch,
    ) {
        @Volatile var seen = false
    }

    private val watches = ConcurrentHashMap<InstanceId, Watched>()

    override fun watch(instanceId: String): InstanceWatch = watches.computeIfAbsent(instanceId) { Watched(InstanceWatch(it)) }.watch

    override fun unwatch(instanceId: String) {
        watches.remove(instanceId)
    }

    /** One poll of every watched instance. */
    fun poll() {
        val current = watches.values.toList()
        current.chunked(reader.batchSize).forEach(::readStates)
        current
            .filter { it.watch.awaitingStatus }
            .chunked(reader.batchSize)
            .forEach(::readStatus)
        current.filter { it.watch.ended.isDone }.forEach { watches.remove(it.watch.instanceId, it) }
    }

    /** Polls every [interval] on a virtual thread until the returned handle is closed. */
    override fun start(): AutoCloseable = Ticker.every("instance-state-watcher", interval) { poll() }

    /** [read], or null when it failed; an interrupt is not a failed read and propagates. */
    private fun <T> readOrSkip(
        what: String,
        read: () -> T,
    ): T? =
        runCatchingInterruptibly(read)
            .onFailure { e -> log.warn(e) { "Could not read $what; reading again next poll" } }
            .getOrNull()

    private fun readStates(batch: List<Watched>) {
        val found =
            readOrSkip("the state of ${batch.size} instances") { reader.describeStates(batch.map { it.watch.instanceId }) }
                ?.associateBy { it.instanceId }
                ?: return
        batch.forEach { watched ->
            val details = found[watched.watch.instanceId]
            when {
                details == null -> if (watched.seen) watched.watch.end(DROPPED)
                details.state in EC2InstanceService.ENDED_STATES -> watched.watch.end(details.state)
                else -> {
                    watched.seen = true
                    val publicIp = details.publicIp
                    if (details.state == RUNNING && !publicIp.isNullOrEmpty()) {
                        watched.watch.markRunning(InstanceAddresses(publicIp, details.privateIp))
                    }
                }
            }
        }
    }

    private fun readStatus(batch: List<Watched>) {
        val passed =
            readOrSkip("the status checks of ${batch.size} instances") {
                reader.passedStatusChecks(batch.map { it.watch.instanceId })
            } ?: return
        batch.filter { it.watch.instanceId in passed }.forEach { it.watch.markStatusOk() }
    }

    /** Constants of the watcher. */
    companion object {
        private val log = KotlinLogging.logger {}
        private const val RUNNING = "running"

        /** The end state reported for an instance EC2 returned once and then stopped returning. */
        const val DROPPED = "no longer returned by EC2"
    }
}
