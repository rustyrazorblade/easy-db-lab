package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.messageOrString
import com.rustyrazorblade.easydblab.runCatchingException
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One database or application node's pipeline (design D2 and D5): running, status checks, SSH,
 * setup (after the workspace files), the image check, registry TLS (after the certificate), join
 * (after the gate, holding a join permit until the node registers), and Ready. A step that does
 * not succeed and that a new instance can fix replaces the node, and the pipeline starts again
 * from running on the new instance; one that cannot be fixed, or a replacement that is refused,
 * ends the node as failed on its last instance, which is kept unless the refused replacement had
 * already terminated it.
 *
 * An [InterruptedException] (the control node failed) stops it at once and propagates.
 */
internal class NodePipeline(
    private val run: BringUpRun,
    private val serverType: ServerType,
    private val first: ClusterHost,
    private val origin: LaunchSink.Origin,
) {
    private val alias = first.alias
    private val startedAt: Instant = run.clock.instant()

    @Volatile private var current = PipelineStep.RUNNING

    /** The instance the node is on now, and its replacement count, for a fault outside any step. */
    @Volatile private var onInstance = first to 0

    /** Brings the node up and returns how it ended. */
    fun run(): NodeEnd =
        try {
            bringUp(first, replacements = 0)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: RuntimeException) {
            // A fault in the coordinator itself, not a step: the node fails, the others go on.
            log.error(e) { "$alias's pipeline failed" }
            val (host, replacements) = onInstance
            failed(NodeEnd.Failed(host, replacements, current, e.messageOrString()))
        }

    private tailrec fun bringUp(
        host: ClusterHost,
        replacements: Int,
    ): NodeEnd {
        onInstance = host to replacements
        val stopped =
            try {
                return once(host, replacements)
            } catch (e: Stopped) {
                e
            }
        if (!stopped.replaceable) return failed(NodeEnd.Failed(stopped.host, replacements, stopped.step, stopped.reason))
        enter(PipelineStep.REPLACING)
        val failure = NodeReplacer.Failure(stopped.step, stopped.reason)
        return when (val outcome = run.replacer.replace(serverType, stopped.host, failure)) {
            is NodeReplacer.Outcome.NotReplaced ->
                failed(NodeEnd.Failed(stopped.host, replacements, stopped.step, outcome.reason, outcome.oldInstanceKept))
            is NodeReplacer.Outcome.Replaced -> {
                run.progress.replaced()
                bringUp(outcome.host, outcome.replacement)
            }
        }
    }

    /** One instance's way through the steps; a step that does not succeed throws [Stopped]. */
    private fun once(
        launched: ClusterHost,
        replacements: Int,
    ): NodeEnd {
        // An instance launched in this `up`, first or replacement, removes the alias's old Node and
        // node password before its agent starts: an instance that had the alias before it may have
        // registered, in this run or an earlier `up`. One found running may own them, so it keeps them.
        // Each join attempt runs on its own thread, so the flag is shared safely across them.
        val cleaned = AtomicBoolean(replacements == 0 && origin == LaunchSink.Origin.FOUND_RUNNING)
        val instance =
            InstanceRun(run, serverType, launched, ::enter) { step, reason, replaceable, host ->
                throw Stopped(step, reason, replaceable, host)
            }
        return instance.use {
            it.reachable()
            if (run.settings.noSetup) return NodeEnd.Provisioned(it.host, replacements)
            it.await(PipelineStep.WORKSPACE, run.workspace)
            setUp(it)
            it.checkImage()
            it.await(PipelineStep.REGISTRY_TLS, run.certificate)
            it.step(PipelineStep.REGISTRY_TLS, run.timing.registryTls) { _ ->
                run.ssh.withSession { run.ports.nodeSteps.configureRegistryTls(it.host) }
            }
            val plane = it.await(PipelineStep.JOIN_WAIT, run.controlPlane)
            val nodes = plane.connection.nodes
            val watch = nodes.watch(alias, it.host.privateIp)
            // Whether the last attempt's agent started, so a failed join says what it was waiting for.
            val started = AtomicBoolean(false)
            // Whether the last attempt reached the join script; an attempt that failed before it has no agent log to read.
            val ran = AtomicBoolean(false)
            val explain = { reason: String -> joinFailure(it.host, ran.get(), started.get(), reason) }
            try {
                run.joins.acquire().use { _ ->
                    if (!cleaned.get()) {
                        // The control plane's old objects are not the instance's fault, so a cleanup
                        // that fails (a Node stuck in deletion) fails the node: a replacement would
                        // find the same objects and waste a replacement slot.
                        it.step(
                            PipelineStep.NODE_CLEANUP,
                            run.timing.join.copy(attempts = 1, replaceable = false),
                            { reason -> "removing the old node $alias from the control plane failed: $reason" },
                        ) { _ -> plane.connection.cleanup(alias) }
                        cleaned.set(true)
                    }
                    it.step(PipelineStep.JOIN, run.timing.join, explain) { attempt ->
                        started.set(false)
                        ran.set(false)
                        run.ssh.withSession {
                            ran.set(true)
                            run.ports.nodeSteps.join(it.host, serverType, plane.access, attempt)
                        }
                        started.set(true)
                        watch.registered.get()
                    }
                }
                it.step(PipelineStep.READINESS, run.timing.ready) { _ -> watch.ready.get() }
            } finally {
                nodes.unwatch(alias)
            }
            ready(it.host, replacements)
        }
    }

    /** The node's setup, inside an SSH session slot. */
    private fun setUp(instance: InstanceRun) {
        instance.step(PipelineStep.SETUP, run.timing.setup) { _ ->
            run.ssh.withSession {
                run.ports.nodeSteps.setup(serverType, instance.host)
            }
        }
    }

    private fun enter(step: PipelineStep) {
        current = step
        run.progress.enter(alias, step)
    }

    private fun ready(
        host: ClusterHost,
        replacements: Int,
    ): NodeEnd {
        run.progress.ready(alias)
        val elapsed = Duration.between(startedAt, run.clock.instant()).toSeconds()
        run.eventBus.emit(Event.Provision.NodeReady(alias, host.instanceId, elapsed, replacements))
        return NodeEnd.Ready(host, replacements)
    }

    private fun failed(end: NodeEnd.Failed): NodeEnd {
        run.progress.failed(alias)
        run.eventBus.emit(Event.Provision.NodeFailed(alias, end.step.wireName, end.reason, end.host.instanceId, end.instanceKept))
        return end
    }

    /**
     * The reason a join of [host] failed: what it waited for (just the [reason] when the attempt never reached the join script), when its agent had [started], and the
     * tail of the agent's log. A log that cannot be read is named, not fatal: the join failed anyway.
     */
    private fun joinFailure(
        host: ClusterHost,
        ran: Boolean,
        started: Boolean,
        reason: String,
    ): String {
        if (!ran) return reason
        val waited = if (started) "its agent started, but no node $alias at ${host.privateIp} registered: $reason" else reason
        val log =
            runCatchingException { run.ssh.withSession { run.ports.nodeSteps.joinLog(host) } }
                .fold({ it.trimEnd().ifBlank { "(empty)" } }, { "(unreadable: ${it.messageOrString()})" })
        return "$waited\nlast lines of the agent log:\n$log"
    }

    /** A step of [host] did not succeed. */
    private class Stopped(
        val step: PipelineStep,
        val reason: String,
        val replaceable: Boolean,
        val host: ClusterHost,
    ) : RuntimeException(reason)

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
