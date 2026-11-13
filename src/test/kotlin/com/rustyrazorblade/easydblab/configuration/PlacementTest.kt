package com.rustyrazorblade.easydblab.configuration

import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The placement an observability workload gets comes from the environment as a value, so a builder
 * never names the control plane itself.
 */
class PlacementTest {
    private val controlPlaneLabel = "node-role.kubernetes.io/control-plane"

    @Test
    fun `an AWS cluster places on the control node with its toleration`() {
        val placement = Placement.from(ClusterState(name = "test", versions = mutableMapOf()))

        assertThat(placement.nodeSelector).containsExactlyEntriesOf(mapOf(controlPlaneLabel to "true"))
        assertThat(placement.tolerations).singleElement().satisfies({
            assertThat(it.key).isEqualTo(controlPlaneLabel)
            assertThat(it.operator).isEqualTo("Exists")
            assertThat(it.effect).isEqualTo("NoSchedule")
        })
        assertThat(placement.hostNetwork).isFalse()
    }

    @Test
    fun `an environment that declares no control node carries no selector or toleration`() {
        assertThat(Placement.ANY_NODE.nodeSelector).isEmpty()
        assertThat(Placement.ANY_NODE.tolerations).isEmpty()
        assertThat(Placement.ANY_NODE.hostNetwork).isFalse()
    }

    @Test
    fun `a node label given at init merges into the selector`() {
        val state =
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                initConfig = InitConfig(nodeLabels = mapOf("pool" to "observability")),
            )

        assertThat(Placement.from(state).nodeSelector)
            .containsExactlyInAnyOrderEntriesOf(mapOf(controlPlaneLabel to "true", "pool" to "observability"))
        assertThat(Placement.ANY_NODE.withNodeLabels(mapOf("pool" to "observability")).nodeSelector)
            .containsExactlyEntriesOf(mapOf("pool" to "observability"))
    }

    @Test
    fun `applying a placement replaces the pod's selector, tolerations and network`() {
        val pod =
            PodSpecBuilder()
                .withHostNetwork(true)
                .withDnsPolicy("ClusterFirstWithHostNet")
                .addToNodeSelector("stale", "yes")
                .addToContainers(
                    ContainerBuilder()
                        .withName("c")
                        .addNewPort()
                        .withContainerPort(1)
                        .withHostPort(1)
                        .endPort()
                        .build(),
                ).build()

        val placed = Placement.ANY_NODE.applyTo(pod)

        assertThat(placed.nodeSelector).isEmpty()
        assertThat(placed.tolerations).isEmpty()
        assertThat(placed.hostNetwork).isFalse()
        assertThat(placed.dnsPolicy).isEqualTo("ClusterFirst")
        assertThat(
            placed.containers
                .single()
                .ports
                .map { it.hostPort },
        ).containsOnlyNulls()
    }

    @Test
    fun `the AWS placement keeps each container's published node port`() {
        val pod =
            PodSpecBuilder()
                .addToContainers(
                    ContainerBuilder()
                        .withName("c")
                        .addNewPort()
                        .withContainerPort(1)
                        .withHostPort(1)
                        .endPort()
                        .build(),
                ).build()

        assertThat(
            Placement.CONTROL_NODE
                .applyTo(pod)
                .containers
                .single()
                .ports
                .single()
                .hostPort,
        ).isEqualTo(1)
    }
}
