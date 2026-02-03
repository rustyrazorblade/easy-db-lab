package com.rustyrazorblade.easydblab.network

import com.rustyrazorblade.easydblab.Constants

/**
 * A value class representing a CIDR block for VPC networking.
 *
 * Provides validation and subnet calculation for VPC CIDR blocks.
 * The CIDR block must be /20 or larger to support /24 subnets for availability zones.
 *
 * Example usage:
 * ```
 * val cidr = CidrBlock("172.16.0.0/16")
 * val subnet0 = cidr.subnetCidr(0)  // "172.16.1.0/24"
 * val subnet1 = cidr.subnetCidr(1)  // "172.16.2.0/24"
 * ```
 */
@JvmInline
value class CidrBlock(
    val value: String,
) {
    init {
        require(isValidCidr(value)) { "Invalid CIDR: $value" }
        require(prefixLength <= MAX_PREFIX_LENGTH) {
            "CIDR prefix must be /$MAX_PREFIX_LENGTH or larger to support /24 subnets, got /$prefixLength"
        }
    }

    /**
     * The prefix length (e.g., 16 for /16, 20 for /20).
     */
    val prefixLength: Int
        get() = value.substringAfter("/").toInt()

    /**
     * The network address without the prefix (e.g., "10.0.0.0" from "10.0.0.0/16").
     */
    val networkAddress: String
        get() = value.substringBefore("/")

    /**
     * Generates a /24 subnet CIDR for the given index within this VPC CIDR.
     *
     * The subnet is created by taking the first two octets of the VPC CIDR
     * and using (index + 1) as the third octet.
     *
     * @param index Zero-based index (0 → .1.0/24, 1 → .2.0/24, etc.)
     * @return Subnet CIDR string (e.g., "10.0.1.0/24")
     */
    fun subnetCidr(index: Int): String {
        require(index >= 0) { "Subnet index must be non-negative" }
        require(index < MAX_SUBNET_INDEX) { "Subnet index must be less than $MAX_SUBNET_INDEX" }

        val octets = networkAddress.split(".").map { it.toInt() }
        return "${octets[0]}.${octets[1]}.${index + 1}.0/$SUBNET_PREFIX_LENGTH"
    }

    override fun toString(): String = value

    companion object {
        /** Default CIDR block for VPCs */
        val DEFAULT = CidrBlock(Constants.Vpc.DEFAULT_CIDR)

        /** Maximum prefix length that can support /24 subnets */
        private const val MAX_PREFIX_LENGTH = 20

        /** Prefix length for subnets */
        private const val SUBNET_PREFIX_LENGTH = 24

        /** Maximum subnet index (third octet can be 1-254) */
        private const val MAX_SUBNET_INDEX = 254

        /** Valid range for octets */
        private const val MIN_OCTET = 0
        private const val MAX_OCTET = 255

        /** Valid range for prefix length */
        private const val MIN_PREFIX = 0
        private const val MAX_PREFIX = 32

        /** Number of octets in an IPv4 address */
        private const val IPV4_OCTET_COUNT = 4

        /**
         * Validates whether a string is a valid IPv4 CIDR block.
         *
         * @param cidr The CIDR string to validate
         * @return true if valid, false otherwise
         */
        fun isValidCidr(cidr: String): Boolean {
            if (!cidr.contains("/")) return false

            val parts = cidr.split("/")
            if (parts.size != 2) return false

            val (address, prefixStr) = parts

            // Validate prefix
            val prefix = prefixStr.toIntOrNull() ?: return false
            if (prefix < MIN_PREFIX || prefix > MAX_PREFIX) return false

            // Validate IP address
            val octets = address.split(".")
            if (octets.size != IPV4_OCTET_COUNT) return false

            return octets.all { octet ->
                val value = octet.toIntOrNull() ?: return false
                value in MIN_OCTET..MAX_OCTET
            }
        }
    }
}
