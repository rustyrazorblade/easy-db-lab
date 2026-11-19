package com.rustyrazorblade.easydblab.network

import com.rustyrazorblade.easydblab.Constants
import kotlin.random.Random

/**
 * A value class representing a CIDR block for VPC networking.
 *
 * Provides validation and subnet calculation for VPC CIDR blocks.  The value is parsed strictly by
 * [Ipv4Cidr], so it is in network form with no host bits set.  It must name its prefix length
 * explicitly.
 *
 * The VPC is split into [Constants.Vpc.MAX_AVAILABILITY_ZONES] equal subnets, one per availability
 * zone, each three bits longer than the VPC prefix (eight /19s for a /16).  The split is fixed;
 * it does not depend on how many zones a cluster uses.  The subnets are large because Cilium's ENI
 * IPAM takes pod IPs from the same subnets as the nodes, so a cluster of 1,000+ nodes needs
 * thousands of addresses per zone.  The VPC prefix must be /25 or larger, so each subnet is at
 * least /28, the smallest subnet AWS allows.  No lower bound is enforced here; AWS itself rejects
 * a VPC larger than /16.
 *
 * Example usage:
 * ```
 * val cidr = CidrBlock("172.16.0.0/16")
 * val subnet0 = cidr.subnetCidr(0)  // "172.16.0.0/19"
 * val subnet1 = cidr.subnetCidr(1)  // "172.16.32.0/19"
 * ```
 */
@JvmInline
value class CidrBlock(
    val value: String,
) {
    init {
        require('/' in value) { "Invalid CIDR: $value has no prefix length; a VPC CIDR needs one, for example /16" }
        Ipv4Cidr.parse(value).getOrThrow()
        require(prefixLength <= MAX_PREFIX_LENGTH) {
            "CIDR prefix must be /$MAX_PREFIX_LENGTH or larger, so it splits into " +
                "${Constants.Vpc.MAX_AVAILABILITY_ZONES} subnets of at least /$MIN_SUBNET_PREFIX_LENGTH; " +
                "got /$prefixLength"
        }
    }

    /**
     * The prefix length (e.g., 16 for /16, 20 for /20).
     */
    val prefixLength: Int
        get() = cidr.prefixLength

    /**
     * The network address without the prefix (e.g., "10.0.0.0" from "10.0.0.0/16").
     */
    val networkAddress: String
        get() = cidr.address

    /** The parsed form, which owns the address arithmetic. */
    private val cidr: Ipv4Cidr
        get() = Ipv4Cidr.parse(value).getOrThrow()

    /**
     * Returns the subnet CIDR for the availability zone at [index].
     *
     * The VPC is split into [Constants.Vpc.MAX_AVAILABILITY_ZONES] equal subnets whose prefix is
     * the VPC prefix plus three; [Ipv4Cidr.subnet] computes the block.
     *
     * @param index Zero-based availability-zone index, 0 to 7 (for a /16: 0 → x.y.0.0/19,
     *   1 → x.y.32.0/19, ..., 7 → x.y.224.0/19)
     * @return Subnet CIDR string (e.g., "10.0.32.0/19")
     * @throws IllegalArgumentException if [index] is negative or names a ninth or later zone
     */
    fun subnetCidr(index: Int): String {
        require(index >= 0) { "Subnet index must be non-negative, got $index" }
        require(index < Constants.Vpc.MAX_AVAILABILITY_ZONES) {
            "A cluster can use at most ${Constants.Vpc.MAX_AVAILABILITY_ZONES} availability zones, " +
                "because the VPC is split into ${Constants.Vpc.MAX_AVAILABILITY_ZONES} subnets; " +
                "subnet index $index is out of range"
        }
        return cidr.subnet(prefixLength + SUBNET_PREFIX_DELTA, index).value
    }

    /** How many addresses each of the VPC's subnets ([subnetCidr]) spans, AWS's reserved five included. */
    fun subnetAddressCount(): Long = 1L shl (ADDRESS_BITS - prefixLength - SUBNET_PREFIX_DELTA)

    /**
     * Whether this block shares any address with K3s's pod range ([Constants.K3s.CLUSTER_CIDR])
     * or service range ([Constants.K3s.SERVICE_CIDR]). A VPC on either would put node IPs and
     * pod or ClusterIP addresses in the same space.
     */
    fun overlapsK3sRanges(): Boolean = K3S_RANGES.any { cidr.overlaps(it) }

    override fun toString(): String = value

    companion object {
        /** Default CIDR block for VPCs */
        val DEFAULT = CidrBlock(Constants.Vpc.DEFAULT_CIDR)

        /**
         * Bits added to the VPC prefix to split it into [Constants.Vpc.MAX_AVAILABILITY_ZONES]
         * (2^3 = 8) equal subnets.
         */
        private const val SUBNET_PREFIX_DELTA = 3

        /** The smallest subnet AWS allows. */
        private const val MIN_SUBNET_PREFIX_LENGTH = 28

        /** The longest VPC prefix whose eight subnets are each still at least /28. */
        private const val MAX_PREFIX_LENGTH = MIN_SUBNET_PREFIX_LENGTH - SUBNET_PREFIX_DELTA

        private const val ADDRESS_BITS = 32

        /** Maximum second octet value when scanning for available 10.X.0.0/16 blocks */
        private const val MAX_SECOND_OCTET = 254

        /**
         * Selects a random `10.X.0.0/16` CIDR not already used by an existing VPC.
         *
         * Picks uniformly among the second octets 0–254 that do not appear in any of the provided
         * CIDR strings, so two workspaces running `up` at the same moment are unlikely to choose
         * the same block. Only `10.x.x.x` CIDRs in [existingCidrs] are considered. A block inside
         * K3s's pod or service range ([overlapsK3sRanges]) is never picked.
         *
         * @param existingCidrs CIDR blocks of all VPCs already present in the region, plus any
         *   block the caller wants excluded (e.g. one whose VPC creation just failed)
         * @param random source of randomness; tests inject a seeded one
         * @throws IllegalStateException if all second octets 0–254 are occupied
         */
        fun selectAvailable(
            existingCidrs: List<String>,
            random: Random,
        ): CidrBlock {
            val usedOctets =
                existingCidrs
                    .filter { it.startsWith("10.") }
                    .mapNotNull { it.split(".").getOrNull(1)?.toIntOrNull() }
                    .toSet()
            val freeOctets =
                (0..MAX_SECOND_OCTET)
                    .filterNot { it in usedOctets }
                    .filterNot { CidrBlock("10.$it.0.0/16").overlapsK3sRanges() }
            check(freeOctets.isNotEmpty()) {
                "No available CIDR blocks in 10.x.0.0/16 range — every second octet 0–254 is in use " +
                    "or inside K3s's pod range ${Constants.K3s.CLUSTER_CIDR} or service range ${Constants.K3s.SERVICE_CIDR}"
            }
            val secondOctet = freeOctets.random(random)
            return CidrBlock("10.$secondOctet.0.0/16")
        }

        /** K3s's pod and service ranges, which no VPC block may overlap. */
        private val K3S_RANGES =
            listOf(Constants.K3s.CLUSTER_CIDR, Constants.K3s.SERVICE_CIDR).map { Ipv4Cidr.parse(it).getOrThrow() }
    }
}
