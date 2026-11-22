package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.Quantity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A node's allocatable memory and CPU, as the kubelet reports them, in the units kits read. */
class NodeAllocatableTest {
    private fun node(allocatable: Map<String, String>) =
        NodeBuilder()
            .withNewMetadata()
            .withName("db0")
            .endMetadata()
            .withNewStatus()
            .withAllocatable<String, Quantity>(allocatable.mapValues { Quantity(it.value) })
            .endStatus()
            .build()

    @Test
    fun `memory in Ki is whole MiB, rounded down, and millicores are whole CPUs, rounded down`() {
        val allocatable = NodeAllocatable.of(node(mapOf("memory" to "32594412Ki", "cpu" to "7900m")))

        assertThat(allocatable).isEqualTo(NodeAllocatable(memoryMib = 31830, cpu = 7))
    }

    @Test
    fun `memory in Gi and a whole CPU count read as given`() {
        assertThat(NodeAllocatable.of(node(mapOf("memory" to "16Gi", "cpu" to "4"))))
            .isEqualTo(NodeAllocatable(memoryMib = 16384, cpu = 4))
    }

    @Test
    fun `a node that reports no allocatable memory is refused, naming it`() {
        assertThatThrownBy { NodeAllocatable.of(node(mapOf("cpu" to "4"))) }
            .hasMessageContaining("db0")
            .hasMessageContaining("memory")
    }

    @Test
    fun `the smallest of several nodes takes each resource's own minimum`() {
        val smallest = NodeAllocatable.smallest(listOf(NodeAllocatable(30000, 8), NodeAllocatable(31000, 6)))

        assertThat(smallest).isEqualTo(NodeAllocatable(memoryMib = 30000, cpu = 6))
    }

    @Test
    fun `an empty pool has nothing allocatable`() {
        assertThat(NodeAllocatable.smallest(emptyList())).isEqualTo(NodeAllocatable.NONE)
    }
}
