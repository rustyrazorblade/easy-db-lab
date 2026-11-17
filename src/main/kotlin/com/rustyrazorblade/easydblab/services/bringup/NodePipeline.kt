package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One database or application node's pipeline (design D2 and D5): running, status checks, SSH,
 * setup, the image check, registry TLS (after the certificate), join (after the gate, holding a
 * join permit until the node registers), and Ready. A step that does not succeed and that a new
 * instance can fix replaces the node, and the pipeline starts again from running on the new
 * instance; one that cannot be fixed, or a replacement that is refused, ends the node as failed
 * on its last instance, which is kept.
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

    /** Brings the node up and returns how it ended. */
    fun run(): NodeEnd =
        try {
            bringUp(first, replacements = 0)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: RuntimeException) {
            // A fault in the coordinator itself, not a step: the node fails, the others go on.
            log.error(e) { "$alias's pipeline failed" }
            failed(NodeEnd.Failed(first, 0, current, e.message ?: e::class.java.simpleName))
        }

    private tailrec fun bringUp(
        host: ClusterHost,
        replacements: Int,
    ): NodeEnd {
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
            is NodeReplacer.Outcome.NotReplaced -> failed(NodeEnd.Failed(stopped.host, replacements, stopped.step, outcome.reason))
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
            setUp(it)
            it.checkImage()
            it.await(PipelineStep.REGISTRY_TLS, run.certificate)
            it.step(PipelineStep.REGISTRY_TLS, run.timing.registryTls) { _ ->
                run.ssh.withSession { run.ports.nodeSteps.configureRegistryTls(it.host) }
            }
            val plane = it.await(PipelineStep.JOIN_WAIT, run.controlPlane)
            val nodes = plane.connection.nodes
            val watch = nodes.watch(alias, it.host.privateIp)
            try {
                run.joins.acquire().use { _ ->
                    it.step(PipelineStep.JOIN, run.timing.join) { attempt ->
                        if (!cleaned.get()) {
                            plane.connection.cleanup(alias)
                            cleaned.set(true)
                        }
                        run.ssh.withSession { run.ports.nodeSteps.join(it.host, serverType, plane.access, attempt) }
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

    /** Setup, and the database versions file once for the whole bring-up, from the first database node. */
    private fun setUp(instance: InstanceRun) {
        instance.step(PipelineStep.SETUP, run.timing.setup) { _ ->
            run.ssh.withSession {
                run.ports.nodeSteps.setup(serverType, instance.host)
                if (serverType == ServerType.Cassandra) run.versions.run { run.ports.nodeSteps.downloadVersions(instance.host) }
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
        return end
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
