package com.rustyrazorblade.easydblab.services.bringup.k3s

import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.CiliumNodeImageCheck
import com.rustyrazorblade.easydblab.services.CiliumService
import com.rustyrazorblade.easydblab.services.K3sClusterService
import com.rustyrazorblade.easydblab.services.K8sManifestOperations
import com.rustyrazorblade.easydblab.services.NodeSetupService
import com.rustyrazorblade.easydblab.services.RegistryService
import com.rustyrazorblade.easydblab.services.bringup.ClusterStateWriter
import com.rustyrazorblade.easydblab.services.bringup.ControlNodeSteps
import com.rustyrazorblade.easydblab.services.bringup.NodeSteps
import com.rustyrazorblade.easydblab.services.bringup.OperatorRoute
import java.io.File

/**
 * What the control node's steps use once the K3s server is up: the manifests that label it, the
 * Cilium install, and the connection to its API server. Kept together because only the control
 * node's steps need them.
 */
class ControlPlaneTools(
    val manifests: K8sManifestOperations,
    val cilium: CiliumService,
    val connector: ControlPlaneConnector,
)

/**
 * Builds one bring-up's K3s steps from the services they run on: [K3sNodeSteps] for every node,
 * and [K3sControlSteps] for the control node. Koin holds it, so a bring-up gets steps bound to its
 * own state writer and workspace.
 */
class K3sBringUpSteps(
    private val remoteOps: RemoteOperationsService,
    private val nodeSetup: NodeSetupService,
    private val imageCheck: CiliumNodeImageCheck,
    private val registry: RegistryService,
    private val k3s: K3sClusterService,
    private val controlPlane: ControlPlaneTools,
) {
    /** The steps every database, application and control node runs. */
    fun nodeSteps(
        state: ClusterStateWriter,
        workspace: File,
    ): NodeSteps = K3sNodeSteps(remoteOps, nodeSetup, imageCheck, registry, k3s, state, workspace)

    /** The control node's own steps, with the operator's side from [route]. */
    fun controlSteps(
        route: OperatorRoute,
        state: ClusterStateWriter,
        workspace: File,
    ): ControlNodeSteps =
        K3sControlSteps(
            route,
            registry,
            k3s,
            ControlNodeNetwork(controlPlane.manifests, controlPlane.cilium, state),
            controlPlane.connector,
            state,
            workspace,
        )
}
