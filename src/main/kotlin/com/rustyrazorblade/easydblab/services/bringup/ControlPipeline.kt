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
            InstanceRun(run, ServerType.Control, launched, onEnter = run.progress::controlEntered) { step, reason, _, host ->
                throw ControlNodeFailedException(host.alias, step.wireName, reason)
            }
        try {
            instance.use { runSteps(it) }
        } finally {
            run.progress.controlEntered(null)
        }
    }

    private fun runSteps(instance: InstanceRun) {
        instance.reachable()
        val steps = run.ports.controlSteps
        val policy = run.timing.controlPolicy
        // Every setup, this one too, installs the environment file, which names db0.
        instance.step(PipelineStep.WORKSPACE, policy) { _ -> steps.writeWorkspace() }
        run.workspace.complete(Unit)
        if (!run.settings.noSetup) {
            instance.step(PipelineStep.SETUP, policy) { _ ->
                run.ssh.withSession { run.ports.nodeSteps.setup(ServerType.Control, instance.host) }
            }
            instance.checkImage()
        }
        instance.step(PipelineStep.TUNNEL, policy) { _ -> steps.connect(instance.host) }
        if (run.settings.noSetup) return
        instance.step(PipelineStep.REGISTRY_CERT, policy) { _ -> run.ssh.withSession { steps.prepareRegistry(instance.host) } }
        run.certificate.complete(Unit)
        val access =
            instance.step(
                PipelineStep.CONTROL_PLANE,
                policy,
            ) { _ -> run.ssh.withSession { steps.startControlPlane(instance.host) } }
        val connection =
            run.own(instance.step(PipelineStep.CONTROL_PLANE_CLIENTS, policy) { _ -> steps.openControlPlane() })
        startHealthMonitor(connection)
        instance.step(PipelineStep.CNI, policy) { _ -> run.ssh.withSession { steps.configureControlPlane(instance.host) } }
        instance.step(PipelineStep.GATE, run.timing.gatePolicy) { _ -> gate(connection, instance.host.alias).await() }
        // The health monitor reports later changes; it may already have seen the control plane unhealthy.
        if (run.health.healthy) run.progress.controlPlane(Event.Provision.ControlPlaneState.HEALTHY)
        run.controlPlane.complete(OpenControlPlane(access, connection))
    }

    private fun gate(
        connection: ControlPlaneConnection,
        alias: String,
    ) = ControlPlaneGate(
        connection.reader,
        run.eventBus,
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
            ) { reason -> onUnhealthyTimeout("$reason. ${connection.recovery}") }
        run.own(Ticker.every("control-plane-health", run.timing.healthInterval, delayFirst = true, monitor::tick))
    }
}
