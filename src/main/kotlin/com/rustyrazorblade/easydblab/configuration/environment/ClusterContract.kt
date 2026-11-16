package com.rustyrazorblade.easydblab.configuration.environment

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.Placement

/**
 * Everything a consumer needs to know about the environment a cluster runs in, as capability data
 * and never as a name. [of] is the only place in main code that branches on the [Environment]
 * variant; every consumer reads a field of this instead, so a new environment adds one branch here.
 *
 * It is resolved from the current state on each use (through [ClusterContractSource]), so an `init`
 * inside the REPL or the server takes effect on the next command.
 *
 * @property environment the recorded environment, for messages only.
 * @property toolingForm how `helm`, `kubectl` and `cilium` run.
 * @property nodeAccessForm how commands reach a node.
 * @property storeKind where the observability backends keep their data.
 * @property networkMode how this machine reaches the cluster.
 * @property nodeCapabilities the recorded capability set, or the environment's declared one.
 * @property hosting where the observability backends and Grafana run, with the `init` node labels.
 * @property namespace the namespace easy-db-lab's workloads run in.
 * @property datacenter the datacenter name the database nodes are given.
 */
data class ClusterContract(
    val environment: Environment,
    val toolingForm: ToolingForm,
    val nodeAccessForm: NodeAccessForm,
    val storeKind: StoreKind,
    val networkMode: NetworkMode,
    val nodeCapabilities: NodeCapabilities,
    val hosting: ObservabilityHosting,
    val namespace: String,
    val datacenter: String,
) {
    companion object {
        /** The contract [state]'s recorded environment resolves to. Pure: it reads only [state]. */
        fun of(state: ClusterState): ClusterContract {
            val environment = state.environment
            val capabilities = NodeCapabilities.from(state)
            val nodeLabels = state.initConfig?.nodeLabels.orEmpty()
            return when (environment) {
                Environment.Ec2K3s ->
                    ClusterContract(
                        environment = environment,
                        toolingForm = ToolingForm.Ssh,
                        nodeAccessForm = NodeAccessForm.Ssh,
                        storeKind = StoreKind.ObjectStore,
                        networkMode = NetworkMode.Tunnel,
                        nodeCapabilities = capabilities,
                        hosting = hosted(Placement.CONTROL_NODE, nodeLabels),
                        namespace = Constants.ClusterContract.OWNED_NAMESPACE,
                        datacenter = state.region(),
                    )
                is Environment.K3sDocker ->
                    ClusterContract(
                        environment = environment,
                        toolingForm = ToolingForm.Local,
                        nodeAccessForm = NodeAccessForm.ContainerExec,
                        storeKind = StoreKind.Volume,
                        networkMode = NetworkMode.Direct,
                        nodeCapabilities = capabilities,
                        // The node is a container: nothing outside the pod network dials its address.
                        hosting = hosted(Placement.CONTROL_NODE.copy(publishOnNode = false), nodeLabels),
                        namespace = Constants.ClusterContract.OWNED_NAMESPACE,
                        datacenter = Constants.ClusterContract.LOCAL_DATACENTER,
                    )
                is Environment.Kubeconfig ->
                    ClusterContract(
                        environment = environment,
                        toolingForm = ToolingForm.Local,
                        nodeAccessForm = NodeAccessForm.None,
                        storeKind = StoreKind.Volume,
                        networkMode = NetworkMode.Direct,
                        nodeCapabilities = capabilities,
                        hosting = hosted(Placement.ANY_NODE, nodeLabels),
                        namespace = Constants.ClusterContract.BROUGHT_NAMESPACE,
                        datacenter = Constants.ClusterContract.LOCAL_DATACENTER,
                    )
            }
        }

        // The storage and credential slots stay AWS's until #1035 and #1061 supply the others.
        private fun hosted(
            placement: Placement,
            nodeLabels: Map<String, String>,
        ): ObservabilityHosting = ObservabilityHosting.AWS.copy(placement = placement.withNodeLabels(nodeLabels))
    }
}
