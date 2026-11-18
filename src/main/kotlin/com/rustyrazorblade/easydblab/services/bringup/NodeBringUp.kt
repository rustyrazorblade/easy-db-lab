package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventContext
import com.rustyrazorblade.easydblab.messageOrString
import com.rustyrazorblade.easydblab.output.NodeOutputTail
import java.time.Clock
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Where `up`'s launch hands each instance as soon as its batch returns, or as discovery finds it. */
fun interface LaunchSink {
    /** [hosts] of [serverType] exist, and came from [origin]; a host already handed over is ignored. */
    fun launched(
        serverType: ServerType,
        hosts: List<ClusterHost>,
        origin: Origin,
    )

    /**
     * Where an instance came from. Its alias may still name a Node and a node password left by an
     * instance an earlier `up` started, which has since gone: a [LAUNCHED] instance removes them
     * before its first join, or K3s refuses it. A [FOUND_RUNNING] one may itself own them.
     */
    enum class Origin {
        FOUND_RUNNING,
        LAUNCHED,
    }
}

/**
 * The bring-up coordinator `up` calls (design D1): one virtual thread per node, each running its
 * own pipeline, started the moment its instance is handed to the [LaunchSink], so a node from the
 * first launch batch starts before the later batches return. It runs the control pipeline, the
 * instance tracker and the progress line, and returns every database and application node's end
 * once the last pipeline ends.
 *
 * When the control node fails (a step after its retries, or the control plane unhealthy past its
 * deadline) every pipeline is interrupted and [run] throws [ControlNodeFailedException]: no node
 * can join without it (design D7). Every instance launched so far stays running and recorded.
 */
class NodeBringUp(
    private val ports: BringUpPortSet,
    private val settings: BringUpSettings,
    private val eventBus: EventBus,
    private val nodeOutput: NodeOutputTail,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Runs [launch], which hands every instance to the sink as it exists, then waits for every
     * pipeline it started. [launch] runs on the calling thread; an exception from it propagates
     * after the pipelines already started are stopped.
     */
    fun run(launch: (LaunchSink) -> Unit): BringUpResult =
        BringUpRun(ports, settings, eventBus, nodeOutput, clock).use { run ->
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                Coordinator(run, executor).bringUp(launch)
            }
        }

    /** One run of the coordinator: the threads it started, and how the control node is doing. */
    private class Coordinator(
        private val run: BringUpRun,
        private val executor: ExecutorService,
    ) : LaunchSink {
        private val commandName = EventContext.current()
        private val nodes = ConcurrentHashMap<String, Future<NodeEnd>>()
        private val controlStarted = CompletableFuture<Unit>()

        /** The control pipeline's thread, interrupted when the bring-up ends early. */
        @Volatile private var controlTask: Future<*>? = null

        /** Completes when the control pipeline finished its steps. */
        private val controlDone = CompletableFuture<Unit>()

        /** Completes with the control node's failure, whenever it happens, even after its pipeline finished. */
        private val controlFailure = CompletableFuture<ControlNodeFailedException>()

        fun bringUp(launch: (LaunchSink) -> Unit): BringUpResult {
            run.own(run.ports.tracker.start())
            run.own(
                Ticker.every("bring-up-progress", run.timing.progressInterval, delayFirst = true) {
                    run.eventBus.emit(run.progress.snapshot())
                },
            )
            try {
                launch(this)
                check(controlStarted.isDone) { "the launch handed over no control node" }
                val ends = nodes.mapValues { (_, future) -> await(future) }
                CompletableFuture.anyOf(controlDone, controlFailure).get()
                controlFailure.getNow(null)?.let { throw it }
                return BringUpResult(ends)
            } finally {
                nodes.values.forEach { it.cancel(true) }
                controlTask?.cancel(true)
            }
        }

        override fun launched(
            serverType: ServerType,
            hosts: List<ClusterHost>,
            origin: LaunchSink.Origin,
        ) {
            when (serverType) {
                ServerType.Control -> hosts.firstOrNull()?.let(::startControl)
                ServerType.Cassandra, ServerType.Stress ->
                    hosts.filter { run.settings.includes(it.alias) }.forEach { host ->
                        nodes.computeIfAbsent(host.alias) { submit { NodePipeline(run, serverType, host, origin).run() } }
                    }
            }
            // A node handed over after the control node failed has nothing to join: stop it too.
            if (controlFailure.isDone) nodes.values.forEach { it.cancel(true) }
        }

        private fun startControl(host: ClusterHost) {
            if (!controlStarted.complete(Unit)) return
            controlTask =
                submit {
                    try {
                        ControlPipeline(run, host) { reason -> controlFailed(ControlNodeFailedException(host.alias, HEALTH, reason)) }
                            .run()
                        controlDone.complete(Unit)
                    } catch (e: ControlNodeFailedException) {
                        controlFailed(e)
                    } catch (e: RuntimeException) {
                        // A fault outside any step still fails the control node, or the coordinator would wait forever.
                        controlFailed(ControlNodeFailedException(host.alias, CONTROL, e.messageOrString()))
                    }
                }
        }

        /** The control node failed: record why first, so every waiter sees it, report it once, then stop every node. */
        private fun controlFailed(failure: ControlNodeFailedException) {
            if (!controlFailure.complete(failure)) return
            run.eventBus.emit(
                Event.Provision.ControlNodeFailed(failure.alias, failure.step, failure.reason, run.nodeOutput.tail(failure.alias)),
            )
            nodes.values.forEach { it.cancel(true) }
        }

        /** A node's end; when the control node failed and stopped it, that failure instead. */
        private fun await(future: Future<NodeEnd>): NodeEnd =
            try {
                future.get()
            } catch (e: CancellationException) {
                throw controlFailure.getNow(null) ?: e
            } catch (e: ExecutionException) {
                throw controlFailure.getNow(null) ?: (e.cause ?: e)
            }

        private fun <T> submit(task: () -> T): Future<T> =
            executor.submit<T> {
                commandName?.let(EventContext::push)
                task()
            }
    }

    private companion object {
        /** The step a control plane unhealthy past its deadline is reported under. */
        const val HEALTH = "control-plane-health"

        /** The step a fault outside the control node's steps is reported under. */
        const val CONTROL = "control-pipeline"
    }
}
