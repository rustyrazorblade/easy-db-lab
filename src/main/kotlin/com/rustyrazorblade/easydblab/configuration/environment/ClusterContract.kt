package com.rustyrazorblade.easydblab.configuration.environment

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.BackendStorage
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.NetworkMode
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.Placement
import com.rustyrazorblade.easydblab.configuration.ProfileRegion
import com.rustyrazorblade.easydblab.configuration.ServicePublishing
import com.rustyrazorblade.easydblab.configuration.StoreCredentials

/**
 * Everything a consumer needs to know about the environment a cluster runs in, as capability data
 * and never as a name. [of] is the only place in main code that branches on the [Environment]
 * variant; every consumer reads a field of this instead, so a new environment adds one branch here.
 * `EnvironmentBranchingTest` fails if any other main class tests the variant.
 *
 * It is resolved from the current state on each use (through [ClusterContractSource]), so an `init`
 * inside the REPL or the server takes effect on the next command.
 *
 * [nodeAccessForm] and [storeKind] are the slots #1039 and #1035 plug into; they are fixed per
 * environment now (design D1), so those changes touch no consumer. [namespace] is the one `init
 * --namespace` recorded, or the environment default. [storeKind] names
 * what the backends are rendered with today, [StoreKind.ObjectStore] in every environment, so it never
 * disagrees with [hosting]; #1035 moves the two together.
 *
 * @property environment the recorded environment, for messages only.
 * @property hasProvisioner whether `up` and `down` can provision and tear it down (#1041 adds K3s in Docker).
 * @property localDataDirectory the directory on this machine the observability data is kept in, or
 *   null where it is kept in the cluster's object store.
 * @property toolingForm how `helm`, `kubectl` and `cilium` run.
 * @property nodeAccessForm how commands reach a node (#1039).
 * @property storeKind where the observability backends keep their data (#1035).
 * @property networkMode how this machine reaches the cluster: on EC2 the mode `init` recorded in
 *   `state.json` (`socks` or `tailscale`); anywhere else always `direct`, whatever the state records.
 * @property nodeCapabilities the recorded capability set, or the environment's declared one.
 * @property hosting where the observability backends and Grafana run, with the `init` node labels.
 * @property namespace the namespace easy-db-lab installs into: the one `init --namespace` recorded, or
 *   [Constants.ContractDefaults.OWNED_NAMESPACE]. A brought cluster keeps that default until the kits
 *   take the cluster namespace, when it becomes [Constants.ContractDefaults.BROUGHT_NAMESPACE] (#1094 D10).
 * @property recordedDatacenter the datacenter name the database nodes are given, or null for an EC2
 *   state that records no region, whose datacenter is the AWS profile's region; read it through
 *   [datacenter], never directly.
 * @property clientNetworkOrigin where the networks a passwordless database kit trusts come from: the
 *   networks easy-db-lab built (EC2), or only what `init` recorded (anywhere else, where the node
 *   network is not known before the cluster exists).
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
    val recordedDatacenter: String?,
    val clientNetworkOrigin: ClientNetworkOrigin,
) {
    /**
     * The datacenter name: the recorded one, or for an EC2 state that records no region the AWS
     * profile's region, the same fallback as [ClusterState.region]. Never blank: with no profile to
     * ask ([ProfileRegion.NONE]) it fails.
     */
    fun datacenter(profileRegion: ProfileRegion): String = recordedDatacenter ?: profileRegion.region()

    companion object {
        /** The contract [state]'s recorded environment resolves to. Pure: it reads only [state]. */
        fun of(state: ClusterState): ClusterContract {
            val environment = state.environment
            val capabilities = NodeCapabilities.from(state)
            val nodeLabels = state.initConfig?.nodeLabels.orEmpty()
            val recordedNamespace = state.initConfig?.namespace
            return when (environment) {
                Environment.Ec2K3s ->
                    ClusterContract(
                        environment = environment,
                        hasProvisioner = true,
                        localDataDirectory = null,
                        toolingForm = ToolingForm.Ssh,
                        nodeAccessForm = NodeAccessForm.Ssh,
                        storeKind = StoreKind.ObjectStore,
                        networkMode = state.networkMode,
                        nodeCapabilities = capabilities,
                        hosting = hosted(Placement.CONTROL_NODE, nodeLabels, ServicePublishing.NodePorts, StoreCredentials.InstanceRole),
                        namespace = recordedNamespace ?: Constants.ContractDefaults.OWNED_NAMESPACE,
                        recordedDatacenter = state.initConfig?.region?.ifBlank { null },
                        clientNetworkOrigin = ClientNetworkOrigin.BuiltNetworks,
                    )
                is Environment.K3sDocker ->
                    ClusterContract(
                        environment = environment,
                        hasProvisioner = false,
                        localDataDirectory = environment.dataDir,
                        toolingForm = ToolingForm.Local,
                        nodeAccessForm = NodeAccessForm.ContainerExec,
                        storeKind = StoreKind.ObjectStore,
                        networkMode = NetworkMode.Direct,
                        nodeCapabilities = capabilities,
                        // The node is a container: the CLI reaches Services by a port-forward, never a node port.
                        hosting = hosted(Placement.CONTROL_NODE, nodeLabels, ServicePublishing.ClusterIp, StoreCredentials.NotProvided),
                        namespace = recordedNamespace ?: Constants.ContractDefaults.OWNED_NAMESPACE,
                        recordedDatacenter = Constants.ContractDefaults.LOCAL_DATACENTER,
                        clientNetworkOrigin = ClientNetworkOrigin.Recorded,
                    )
                is Environment.Kubeconfig ->
                    ClusterContract(
                        environment = environment,
                        hasProvisioner = false,
                        localDataDirectory = null,
                        toolingForm = ToolingForm.Local,
                        nodeAccessForm = NodeAccessForm.None,
                        storeKind = StoreKind.ObjectStore,
                        networkMode = NetworkMode.Direct,
                        nodeCapabilities = capabilities,
                        hosting = hosted(Placement.ANY_NODE, nodeLabels, ServicePublishing.ClusterIp, StoreCredentials.NotProvided),
                        // Stays at the owned default until the kits take the cluster namespace (design D10).
                        namespace = recordedNamespace ?: Constants.ContractDefaults.OWNED_NAMESPACE,
                        recordedDatacenter = Constants.ContractDefaults.LOCAL_DATACENTER,
                        clientNetworkOrigin = ClientNetworkOrigin.Recorded,
                    )
            }
        }

        /**
         * Every environment's backends keep their local state in a host directory and name the account
         * bucket, which is what [StoreKind.ObjectStore] names: #1035 changes the store kind and the storage
         * together. Only an EC2 node has an instance role to reach it with; elsewhere the [credentials] are
         * [StoreCredentials.NotProvided] until #1061 provisions them.
         */
        private fun hosted(
            placement: Placement,
            nodeLabels: Map<String, String>,
            publishing: ServicePublishing,
            credentials: StoreCredentials,
        ): ObservabilityHosting =
            ObservabilityHosting(
                placement = placement.withNodeLabels(nodeLabels),
                storage = BackendStorage.HostPath,
                credentials = credentials,
                publishing = publishing,
            )
    }
}
