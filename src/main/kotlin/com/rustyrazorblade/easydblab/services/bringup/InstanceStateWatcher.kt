package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.providers.aws.InstanceDetails
import com.rustyrazorblade.easydblab.providers.aws.InstanceId
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.InstanceStateReader
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one EC2 poller every node pipeline shares (design D9). Each poll makes one `DescribeInstances`
 * and one `DescribeInstanceStatus` request per [InstanceStateReader.batchSize] watched instances,
 * so 1,000 pipelines cost 20 requests a poll, not 2,000.
 *
 * Each watched instance gets an [InstanceWatch]: its `running` future completes once EC2 reports it
 * running with a public IP, its `statusOk` future once both status checks pass, and its `ended`
 * future when EC2 reports it shutting down, terminated, stopping or stopped, or stops returning an
 * instance it had returned. An instance stays watched after it is running and checked, so its end
 * is seen in whatever step the pipeline is in, until the pipeline [unwatch]es it.
 *
 * A failed read is logged and skipped: the next poll reads again, and nothing is ended by it.
 */
class InstanceStateWatcher(
    private val reader: InstanceStateReader,
    private val interval: Duration = Duration.ofMillis(EC2InstanceService.POLL_INTERVAL_MS),
) {
    private val watches = ConcurrentHashMap<InstanceId, InstanceWatch>()

    /** What one pipeline waits on for its instance. */
    class InstanceWatch internal constructor(
        val instanceId: InstanceId,
    ) {
        val running = CompletableFuture<InstanceDetails>()
        val statusOk = CompletableFuture<Unit>()
        val ended = CompletableFuture<String>()
        internal val seen = AtomicBoolean()

        internal fun end(state: String) {
            val failure = IllegalStateException("instance $instanceId ended: $state")
            running.completeExceptionally(failure)
            statusOk.completeExceptionally(failure)
            ended.complete(state)
        }
    }

    /** Starts watching [instanceId]; watching it again returns the same watch. */
    fun watch(instanceId: InstanceId): InstanceWatch = watches.computeIfAbsent(instanceId, ::InstanceWatch)

    /** Stops watching [instanceId]: the pipeline is done with it, or replaced it. */
    fun unwatch(instanceId: InstanceId) {
        watches.remove(instanceId)
    }

    /** One poll of every watched instance. */
    fun poll() {
        val current = watches.values.toList()
        current.chunked(reader.batchSize).forEach(::readStates)
        current
            .filter { it.running.isDone && !it.running.isCompletedExceptionally && !it.statusOk.isDone }
            .chunked(reader.batchSize)
            .forEach(::readStatus)
        current.filter { it.ended.isDone }.forEach { watches.remove(it.instanceId, it) }
    }

    /** Polls every [interval] on a virtual thread until the returned handle is closed. */
    fun start(): AutoCloseable {
        val poller =
            Thread.ofVirtual().name("instance-state-watcher").start {
                while (!Thread.currentThread().isInterrupted) {
                    poll()
                    try {
                        Thread.sleep(interval)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        return AutoCloseable {
            poller.interrupt()
            poller.join()
        }
    }

    /** [read], or null when it failed; an interrupt is not a failed read and propagates. */
    private fun <T> readOrSkip(
        what: String,
        read: () -> T,
    ): T? =
        try {
            read()
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Could not read $what; reading again next poll" }
            null
        }

    private fun readStates(batch: List<InstanceWatch>) {
        val found =
            readOrSkip("the state of ${batch.size} instances") { reader.describeStates(batch.map { it.instanceId }) }
                ?.associateBy { it.instanceId }
                ?: return
        batch.forEach { watch ->
            val details = found[watch.instanceId]
            when {
                details == null -> if (watch.seen.get()) watch.end(DROPPED)
                details.state in EC2InstanceService.ENDED_STATES -> watch.end(details.state)
                else -> {
                    watch.seen.set(true)
                    if (details.state == RUNNING && !details.publicIp.isNullOrEmpty()) watch.running.complete(details)
                }
            }
        }
    }

    private fun readStatus(batch: List<InstanceWatch>) {
        val passed =
            readOrSkip("the status checks of ${batch.size} instances") { reader.passedStatusChecks(batch.map { it.instanceId }) }
                ?: return
        batch.filter { it.instanceId in passed }.forEach { it.statusOk.complete(Unit) }
    }

    /** Constants of the watcher. */
    companion object {
        private val log = KotlinLogging.logger {}
        private const val RUNNING = "running"

        /** The end state reported for an instance EC2 returned once and then stopped returning. */
        const val DROPPED = "no longer returned by EC2"
    }
}
