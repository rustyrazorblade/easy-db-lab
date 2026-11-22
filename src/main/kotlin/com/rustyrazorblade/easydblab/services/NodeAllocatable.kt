package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.Quantity
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * What a node can give its pods: the kubelet's `status.allocatable` memory in whole MiB and CPU as a
 * whole count, both rounded down. A kit sizes a pod from the smallest db node ([smallest]), so the
 * pod fits on every one of them (`DB_NODE_ALLOCATABLE_MIB`, `DB_NODE_ALLOCATABLE_CPU`).
 */
data class NodeAllocatable(
    val memoryMib: Long,
    val cpu: Long,
) {
    companion object {
        /** An empty pool: nothing allocatable, so a kit falls back to its fixed defaults. */
        val NONE = NodeAllocatable(memoryMib = 0, cpu = 0)

        private val MIB = BigDecimal(1024 * 1024)

        /**
         * [node]'s allocatable memory and CPU.
         *
         * @throws IllegalStateException naming the node and the resource when the kubelet reports none
         */
        fun of(node: Node): NodeAllocatable {
            val allocatable = node.status?.allocatable.orEmpty()

            fun amount(resource: String): BigDecimal =
                allocatable[resource]?.let(Quantity::getAmountInBytes)
                    ?: error("Node ${node.metadata?.name} reports no allocatable $resource")
            return NodeAllocatable(
                memoryMib = amount("memory").divide(MIB, 0, RoundingMode.FLOOR).toLong(),
                cpu = amount("cpu").setScale(0, RoundingMode.FLOOR).toLong(),
            )
        }

        /** Each resource's minimum across [nodes], or [NONE] for no nodes. */
        fun smallest(nodes: List<NodeAllocatable>): NodeAllocatable =
            when (nodes.isEmpty()) {
                true -> NONE
                false -> NodeAllocatable(nodes.minOf { it.memoryMib }, nodes.minOf { it.cpu })
            }
    }
}
