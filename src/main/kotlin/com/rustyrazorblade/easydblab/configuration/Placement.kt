package com.rustyrazorblade.easydblab.configuration

import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ContainerPort
import io.fabric8.kubernetes.api.model.ContainerPortBuilder
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.Toleration
import io.fabric8.kubernetes.api.model.TolerationBuilder

/**
 * Where the observability backends, Grafana and kube-state-metrics run, as a value the environment
 * resolves once. The builders apply it instead of naming the control plane themselves, so the same
 * manifests run on a cluster easy-db-lab built and on one it did not.
 *
 * Every workload it places is on the pod network ([hostNetwork] is always false here) and is reached
 * in the cluster by its Service DNS name.
 *
 * @property nodeSelector the labels a node must carry.
 * @property tolerations the taints the pods tolerate.
 * @property hostNetwork whether the pods share the node's network; false for every workload placed
 *   with this type.
 * @property publishOnNode whether each container port that declares a `hostPort` keeps it, so a
 *   caller outside the pod network (a node-side agent, or the CLI over the SOCKS tunnel) still
 *   reaches the workload on its node's private IP. Only an environment that owns its nodes does.
 */
data class Placement(
    val nodeSelector: Map<String, String>,
    val tolerations: List<Toleration>,
    val hostNetwork: Boolean = false,
    val publishOnNode: Boolean = false,
) {
    /** This placement with [labels] added to its node selector. */
    fun withNodeLabels(labels: Map<String, String>): Placement = copy(nodeSelector = nodeSelector + labels)

    /**
     * [pod] placed here: its selector, tolerations and network replaced by this placement's, and its
     * host ports dropped unless [publishOnNode].
     */
    fun applyTo(pod: PodSpec): PodSpec =
        PodSpecBuilder(pod)
            .withNodeSelector<String, String>(nodeSelector)
            .withTolerations(tolerations)
            .withHostNetwork(hostNetwork)
            .withDnsPolicy(if (hostNetwork) "ClusterFirstWithHostNet" else "ClusterFirst")
            .withContainers(pod.containers.map { ContainerBuilder(it).withPorts(placePorts(it.ports.orEmpty())).build() })
            .build()

    private fun placePorts(ports: List<ContainerPort>) =
        when (publishOnNode) {
            true -> ports
            false -> ports.map { ContainerPortBuilder(it).withHostPort(null).build() }
        }

    companion object {
        private const val CONTROL_PLANE_LABEL = "node-role.kubernetes.io/control-plane"

        /** The K3s control node of a cluster easy-db-lab built, with its ports on the node's private IP. */
        val CONTROL_NODE =
            Placement(
                nodeSelector = mapOf(CONTROL_PLANE_LABEL to "true"),
                tolerations =
                    listOf(
                        TolerationBuilder()
                            .withKey(CONTROL_PLANE_LABEL)
                            .withOperator("Exists")
                            .withEffect("NoSchedule")
                            .build(),
                    ),
                publishOnNode = true,
            )

        /** Any schedulable node: an environment that declares no control node. */
        val ANY_NODE = Placement(nodeSelector = emptyMap(), tolerations = emptyList())

        /**
         * The placement for [state]'s environment, with the node labels given at `init` merged in.
         * Every cluster is AWS until the execution environment is recorded in state (#1040), which
         * will make this return the recorded environment's placement.
         */
        fun from(state: ClusterState): Placement = CONTROL_NODE.withNodeLabels(state.initConfig?.nodeLabels.orEmpty())
    }
}
