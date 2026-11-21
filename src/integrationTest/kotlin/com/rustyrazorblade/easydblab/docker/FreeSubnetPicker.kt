package com.rustyrazorblade.easydblab.docker

import com.rustyrazorblade.easydblab.network.Ipv4Cidr

/**
 * Picks a block for a test's own Docker network that no network already on the Docker host
 * overlaps. A shared Docker host holds other projects' networks anywhere in the private ranges
 * (one took all of `172.30.0.0/16`), so the search covers every private range, not one slice.
 */
object FreeSubnetPicker {
    /** The RFC 1918 ranges, searched in this order. */
    val PRIVATE_RANGES: List<Ipv4Cidr> =
        listOf("172.16.0.0/12", "10.0.0.0/8", "192.168.0.0/16").map { Ipv4Cidr.parse(it).getOrThrow() }

    private const val MAX_PREFIX = 32

    /**
     * The first `/[prefix]` block of [ranges], in order, that no CIDR in [taken] overlaps, or null
     * when every block is taken. A taken network is skipped whole rather than block by block, so a
     * `/8` that is already in use costs one step.
     */
    fun pick(
        taken: List<Ipv4Cidr>,
        prefix: Int,
        ranges: List<Ipv4Cidr> = PRIVATE_RANGES,
    ): Ipv4Cidr? {
        for (range in ranges) {
            val blockCount = 1L shl (prefix - range.prefixLength)
            val blockSize = 1L shl (MAX_PREFIX - prefix)
            var index = 0L
            while (index < blockCount) {
                val block = range.subnet(prefix, index.toInt())
                val blocker = taken.firstOrNull { it.overlaps(block) } ?: return block
                val blockerEnd = blocker.networkBits + blocker.addressCount
                val firstPastBlocker = (blockerEnd - range.networkBits + blockSize - 1) / blockSize
                index = maxOf(index + 1, firstPastBlocker)
            }
        }
        return null
    }
}
