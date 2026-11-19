package com.rustyrazorblade.easydblab.services.bringup.k3s

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.kubernetes.WorkspaceKubeconfig
import com.rustyrazorblade.easydblab.services.CiliumService
import com.rustyrazorblade.easydblab.services.K3sClusterService
import com.rustyrazorblade.easydblab.services.K8sManifestOperations
import com.rustyrazorblade.easydblab.services.RegistryService
import com.rustyrazorblade.easydblab.services.bringup.ClusterStateWriter
import com.rustyrazorblade.easydblab.services.bringup.ControlNodeSteps
import com.rustyrazorblade.easydblab.services.bringup.ControlPlaneAccess
import com.rustyrazorblade.easydblab.services.bringup.ControlPlaneConnection
import com.rustyrazorblade.easydblab.services.bringup.OperatorRoute
import java.io.File

/**
 * [ControlNodeSteps] for a K3s control plane: the registry certificate, the K3s server and the
 * workspace kubeconfig, the fabric8 clients, and the control node's network ([ControlNodeNetwork]).
 * The workspace files and the operator's route come from [route], which the command running the
 * bring-up provides.
 *
 * @param workspace the cluster workspace, where the kubeconfig is written
 */
class K3sControlSteps(
    private val route: OperatorRoute,
    private val registry: RegistryService,
    private val k3s: K3sClusterService,
    private val network: ControlNodeNetwork,
    private val connector: ControlPlaneConnector,
    private val state: ClusterStateWriter,
    private val workspace: File,
) : ControlNodeSteps,
    OperatorRoute by route {
    override fun prepareRegistry(control: ClusterHost) {
        val bucket = state.read { checkNotNull(it.s3Bucket) { "The S3 bucket must be configured before registry TLS" } }
        val host = control.toHost()
        registry.generateAndUploadCert(host, bucket)
        registry.configureTlsOnNode(host, control.privateIp, bucket)
    }

    override fun startControlPlane(control: ClusterHost): ControlPlaneAccess {
        val kubeconfig = WorkspaceKubeconfig.file(workspace).toPath()
        val cluster = state.read { it }
        val server = k3s.startServer(control, kubeconfig, cluster, useCustomCni = state.read(::cniOf) == CniMode.Cilium)
        // Pin the written kubeconfig's context in the state, so every later command targets it,
        // whatever the user's kubectl context is.
        server.kubeContext?.let { context -> state.update { it.kubeContext = context } }
        return ControlPlaneAccess(server.url, server.token)
    }

    override fun openControlPlane(): ControlPlaneConnection = connector.open(state.read(::cniOf))

    override fun configureControlPlane(control: ClusterHost) = network.configure(control.toHost())
}

/**
 * The control node's place in the cluster network once K3s runs: its `type=control` label (the
 * OTel `k8s_attributes` processor reads it; db and app nodes carry theirs from their agent
 * config), and under Cilium the Cilium install and, on a tailnet cluster, the Tailscale
 * masquerade chain.
 */
class ControlNodeNetwork(
    private val manifests: K8sManifestOperations,
    private val cilium: CiliumService,
    private val state: ClusterStateWriter,
) {
    /**
     * Labels [host] and installs the network plugin it needs. It takes the SSH [Host], not the
     * `ClusterHost`: the Kubernetes label is by node name, and only Cilium's install reaches the node.
     */
    fun configure(host: Host) {
        manifests.labelNode(host.alias, mapOf(NODE_TYPE_LABEL to CONTROL_NODE_TYPE)).getOrThrow()
        if (state.read(::cniOf) != CniMode.Cilium) return
        val cidr = state.read { checkNotNull(it.initConfig?.cidr) { "The VPC CIDR must be resolved before installing Cilium" } }
        cilium.install(host, cidr).getOrThrow()
        // The control node is the tailnet subnet router. Cilium's NAT chain ACCEPTs tailnet packets
        // to the db nodes before Tailscale can masquerade them, so the db nodes are unreachable over
        // the tailnet until this chain is in place.
        if (state.read { it.isTailscaleEnabled() }) cilium.installTailscaleMasquerade(host).getOrThrow()
    }

    private companion object {
        const val NODE_TYPE_LABEL = "type"
        const val CONTROL_NODE_TYPE = "control"
    }
}

/** The cluster's network plugin; a state that records none is read as Flannel. */
internal fun cniOf(cluster: ClusterState): CniMode = cluster.initConfig?.cni ?: CniMode.Flannel
