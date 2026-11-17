package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import java.util.concurrent.CompletableFuture

/**
 * What a node joins with: the control plane's address and the credential a node presents. It is
 * produced by [ControlNodeSteps.startControlPlane] and passed through to [NodeSteps.join] unread,
 * so the coordinator never knows how a node joins. The token is never printed.
 */
data class ControlPlaneAccess(
    val url: String,
    val token: String,
) {
    override fun toString(): String = "ControlPlaneAccess(url=$url, token=***)"
}

/**
 * The per-node work of a bring-up, after the provider runs the instance (design D2 and D12): a
 * port, so `NodeBringUp` holds no K3s, Cilium or SSH specifics and the tests drive it with a fake.
 * Every call runs on the node's own pipeline thread and may block; a failure is thrown.
 */
interface NodeSteps {
    /** Whether [host] answers over SSH now; false while it boots, a throw when it never will. */
    fun sshAnswers(host: ClusterHost): Boolean

    /** Sets [host] up as a node of [serverType]. */
    fun setup(
        serverType: ServerType,
        host: ClusterHost,
    )

    /**
     * Checks that [host]'s image carries what the cluster needs; null when it does, or when the
     * cluster needs nothing of the image. A defect rules out a replacement: a new instance has the
     * same image. A check that cannot run throws, and is retried and replaced like any other step.
     */
    fun checkImage(host: ClusterHost): ImageDefect?

    /** Makes [host] trust the cluster's container registry; the registry certificate exists. */
    fun configureRegistryTls(host: ClusterHost)

    /**
     * Starts [host]'s join as a [serverType] node with [access]. [attempt] 1 starts the agent, a
     * later one restarts it. It returns once the agent is started, not once it has registered.
     */
    fun join(
        host: ClusterHost,
        serverType: ServerType,
        access: ControlPlaneAccess,
        attempt: Int,
    )

    /** The last lines of [host]'s join agent log, for the reason of a join that failed. */
    fun joinLog(host: ClusterHost): String
}

/**
 * What a node's image lacks, as [NodeSteps.checkImage] found: the [reason] the node fails with, and the
 * [report] event that says how to fix the image, emitted once when the node fails.
 */
data class ImageDefect(
    val reason: String,
    val report: Event,
)

/**
 * The operator's side of the control node's steps: the workspace files and the route from this
 * machine into the cluster. The command running the bring-up provides it, because both are about
 * the workspace and the operator's machine, not the cluster.
 */
interface OperatorRoute {
    /** Writes the workspace files (SSH config, environment files, database config) for the hosts known now. */
    fun writeWorkspace()

    /** Opens the operator's route to the cluster: the VPN and its route check, or the SOCKS tunnel. */
    fun connect(control: ClusterHost)
}

/**
 * The control node's own steps (design D2): the workspace files, the operator's route into the
 * cluster, the registry, the control plane and its network plugin. A port, as [NodeSteps] is.
 */
interface ControlNodeSteps : OperatorRoute {
    /** Creates the registry certificate and makes [control] serve it. */
    fun prepareRegistry(control: ClusterHost)

    /** Starts the control plane on [control], writes the workspace kubeconfig, and returns what nodes join with. */
    fun startControlPlane(control: ClusterHost): ControlPlaneAccess

    /** Opens the clients the coordinator reads the control plane through; the kubeconfig exists. */
    fun openControlPlane(): ControlPlaneConnection

    /** Labels [control] and installs the network plugin, once the control plane is up. */
    fun configureControlPlane(control: ClusterHost)
}

/**
 * The coordinator's view of a running control plane: what the gate reads, the health samples,
 * each node's registration and readiness, and the removal of a replaced node's objects.
 */
interface ControlPlaneConnection : AutoCloseable {
    val reader: ControlPlaneReader
    val sampler: HealthSampler
    val nodes: NodeTracker

    /** What the operator runs to recover a control plane that stays unhealthy, as one sentence. */
    val recovery: String

    /** Removes [alias]'s cluster objects, so a replacement can register under the alias. */
    fun cleanup(alias: String)
}

/** Takes one health sample of the control plane. */
fun interface HealthSampler {
    fun sample(): ControlPlaneHealth.Sample
}

/**
 * Each node's registration and readiness, as every pipeline waits on them (design D9). The
 * K3s adapter watches Nodes over fabric8.
 */
interface NodeTracker {
    /** Watches the node named [alias] at [privateIp], replacing any earlier watch of [alias]. */
    fun watch(
        alias: String,
        privateIp: String,
    ): NodeWatch

    /** Stops watching [alias]. */
    fun unwatch(alias: String)
}

/**
 * What one pipeline waits on for its node: [registered] once a node named [alias] with
 * [privateIp] exists, and [ready] once it is Ready. Matching the IP as well as the name keeps a
 * replacement, which reuses the alias, from being completed by the node it replaced.
 */
class NodeWatch(
    val alias: String,
    val privateIp: String,
) {
    val registered = CompletableFuture<Unit>()
    val ready = CompletableFuture<Unit>()
}
