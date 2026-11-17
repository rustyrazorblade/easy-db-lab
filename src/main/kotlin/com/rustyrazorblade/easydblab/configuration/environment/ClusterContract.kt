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
 * `EnvironmentBranchingTest` fails if any other main class tests the variant.
 *
 * It is resolved from the current state on each use (through [ClusterContractSource]), so an `init`
 * inside the REPL or the server takes effect on the next command.
 *
 * [nodeAccessForm], [storeKind] and [namespace] are the slots #1039, #1035 and #1094 plug into; they
 * are fixed per environment now (design D1), so those changes touch no consumer.
 *
 * @property environment the recorded environment, for messages only.
 * @property hasProvisioner whether `up` and `down` can provision and tear it down (#1041 adds K3s in Docker).
 * @property localDataDirectory the directory on this machine the observability data is kept in, or
 *   null where it is kept in the cluster's object store.
 * @property toolingForm how `helm`, `kubectl` and `cilium` run.
 * @property nodeAccessForm how commands reach a node (#1039).
 * @property storeKind where the observability backends keep their data (#1035).
 * @property networkMode how this machine reaches the cluster.
 * @property nodeCapabilities the recorded capability set, or the environment's declared one.
 * @property hosting where the observability backends and Grafana run, with the `init` node labels.
 * @property namespace the namespace easy-db-lab's workloads run in (#1094 adds the flag).
 * @property datacenter the datacenter name the database nodes are given; blank for an EC2 state
 *   that records no region.
 */
data class ClusterContract(
    val environment: Environment,
    val hasProvisioner: Boolean,
    val localDataDirectory: String?,
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
                        hasProvisioner = true,
                        localDataDirectory = null,
                        toolingForm = ToolingForm.Ssh,
                        nodeAccessForm = NodeAccessForm.Ssh,
                        storeKind = StoreKind.ObjectStore,
                        networkMode = NetworkMode.Tunnel,
                        nodeCapabilities = capabilities,
                        hosting = hosted(Placement.CONTROL_NODE, nodeLabels),
                        namespace = Constants.ContractDefaults.OWNED_NAMESPACE,
                        datacenter = state.initConfig?.region.orEmpty(),
                    )
                is Environment.K3sDocker ->
                    ClusterContract(
                        environment = environment,
                        hasProvisioner = false,
                        localDataDirectory = environment.dataDir,
                        toolingForm = ToolingForm.Local,
                        nodeAccessForm = NodeAccessForm.ContainerExec,
                        storeKind = StoreKind.Volume,
                        networkMode = NetworkMode.Direct,
                        nodeCapabilities = capabilities,
                        // The node is a container: nothing outside the pod network dials its address.
                        hosting = hosted(Placement.CONTROL_NODE.copy(publishOnNode = false), nodeLabels),
                        namespace = Constants.ContractDefaults.OWNED_NAMESPACE,
                        datacenter = Constants.ContractDefaults.LOCAL_DATACENTER,
                    )
                is Environment.Kubeconfig ->
                    ClusterContract(
                        environment = environment,
                        hasProvisioner = false,
                        localDataDirectory = null,
                        toolingForm = ToolingForm.Local,
                        nodeAccessForm = NodeAccessForm.None,
                        storeKind = StoreKind.Volume,
                        networkMode = NetworkMode.Direct,
                        nodeCapabilities = capabilities,
                        hosting = hosted(Placement.ANY_NODE, nodeLabels),
                        namespace = Constants.ContractDefaults.BROUGHT_NAMESPACE,
                        datacenter = Constants.ContractDefaults.LOCAL_DATACENTER,
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
