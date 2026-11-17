package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event

/**
 * The control node's pipeline (design D2): running, status checks and SSH; the workspace files
 * once every launch returned (they need the control node's address, and every setup installs
 * them); setup and the image check; the operator's route into the cluster; the registry
 * certificate, which releases every node's registry TLS; the control plane, whose clients it
 * opens and whose health it starts sampling; the label and the network plugin; and the gate,
 * which releases every node's join. Under `--no-setup` it ends after the route.
 *
 * The control node is never replaced (design D7): any step that does not succeed after its
 * retries throws [ControlNodeFailedException], and the coordinator stops every pipeline.
 */
internal class ControlPipeline(
    private val run: BringUpRun,
    private val launched: ClusterHost,
    private val onUnhealthyTimeout: (reason: String) -> Unit,
) {
    /** Brings the control node up; throws [ControlNodeFailedException] when it cannot. */
    fun run() {
        val instance =
            InstanceRun(run, ServerType.Control, launched, onEnter = {}) { step, reason, _, host ->
                throw ControlNodeFailedException(host.alias, step.wireName, reason)
            }
        instance.use {
            it.reachable()
            val steps = run.ports.controlSteps
            val policy = run.timing.controlPolicy
            // Every setup, this one too, installs the environment file, which names db0.
            it.step(PipelineStep.WORKSPACE, policy) { _ -> steps.writeWorkspace() }
            run.workspace.complete(Unit)
            if (!run.settings.noSetup) {
                it.step(PipelineStep.SETUP, policy) { _ -> run.ssh.withSession { run.ports.nodeSteps.setup(ServerType.Control, it.host) } }
                it.checkImage()
            }
            it.step(PipelineStep.TUNNEL, policy) { _ -> steps.connect(it.host) }
            if (run.settings.noSetup) return
            it.step(PipelineStep.REGISTRY_CERT, policy) { _ -> run.ssh.withSession { steps.prepareRegistry(it.host) } }
            run.certificate.complete(Unit)
            val access = it.step(PipelineStep.K3S_SERVER, policy) { _ -> run.ssh.withSession { steps.startControlPlane(it.host) } }
            val connection = run.own(it.step(PipelineStep.K3S_SERVER, policy) { _ -> steps.openControlPlane() })
            run.connection.complete(connection)
            startHealthMonitor(connection)
            it.step(PipelineStep.CNI, policy) { _ -> steps.configureControlPlane(it.host) }
            it.step(PipelineStep.GATE, run.timing.gatePolicy) { _ -> gate(connection, it.host.alias).await() }
            // The health monitor reports later changes; it may already have seen the control plane unhealthy.
            if (run.health.healthy) run.progress.controlPlane(Event.Provision.ControlPlaneState.HEALTHY)
            run.controlPlane.complete(OpenControlPlane(access, connection))
        }
    }

    private fun gate(
        connection: ControlPlaneConnection,
        alias: String,
    ) = ControlPlaneGate(
        connection.reader,
        run.eventBus,
        run.settings.cni,
        alias,
        run.timing.gateTimeout,
        run.timing.gateInterval,
        run.clock,
    )

    private fun startHealthMonitor(connection: ControlPlaneConnection) {
        val monitor =
            ControlPlaneHealthMonitor(
                connection.sampler,
                ControlPlaneHealth(unhealthyTimeout = run.timing.unhealthyTimeout),
                run.health,
                run.progress,
                run.eventBus,
                run.clock,
                onUnhealthyTimeout,
            )
        run.own(Ticker.every("control-plane-health", run.timing.healthInterval, delayFirst = true, monitor::tick))
    }
}
