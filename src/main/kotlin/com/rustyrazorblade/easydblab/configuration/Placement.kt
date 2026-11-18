package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.Toleration
import io.fabric8.kubernetes.api.model.TolerationBuilder

/**
 * Where the observability backends, Grafana and kube-state-metrics run, as a value the environment
 * resolves once. The builders apply it instead of naming the control plane themselves, so the same
 * manifests run on a cluster easy-db-lab built and on one it did not.
 *
 * Every workload it places is on the pod network and is reached in the cluster by its Service DNS
 * name; callers outside the pod network reach it through its Service's NodePort
 * ([ServicePublishing]), never a host port.
 *
 * @property nodeSelector the labels a node must carry.
 * @property tolerations the taints the pods tolerate.
 */
data class Placement(
    val nodeSelector: Map<String, String>,
    val tolerations: List<Toleration>,
) {
    /** This placement with [labels] added to its node selector. */
    fun withNodeLabels(labels: Map<String, String>): Placement = copy(nodeSelector = nodeSelector + labels)

    /** [pod] placed here: its selector and tolerations replaced by this placement's, on the pod network. */
    fun applyTo(pod: PodSpec): PodSpec =
        PodSpecBuilder(pod)
            .withNodeSelector<String, String>(nodeSelector)
            .withTolerations(tolerations)
            .withHostNetwork(false)
            .withDnsPolicy("ClusterFirst")
            .build()

    companion object {
        private const val CONTROL_PLANE_LABEL = "node-role.kubernetes.io/control-plane"

        /** The K3s control node of a cluster easy-db-lab built. */
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
            )

        /** Any schedulable node: an environment that declares no control node. */
        val ANY_NODE = Placement(nodeSelector = emptyMap(), tolerations = emptyList())

        /**
         * The placement the cluster contract resolves for [state]'s recorded environment, with the
         * node labels given at `init` merged in.
         */
        fun from(state: ClusterState): Placement = ClusterContract.of(state).hosting.placement
    }
}
