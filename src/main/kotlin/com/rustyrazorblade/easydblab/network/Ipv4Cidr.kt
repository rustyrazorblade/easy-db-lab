package com.rustyrazorblade.easydblab.network

/**
 * An IPv4 CIDR in canonical network form, for example `198.51.100.0/24`.
 *
 * It is the one strict parser for every CIDR an operator types: the SSH ingress values of
 * `ingress allow` and `ingress revoke`, and the VPC block of `init --cidr` (through [CidrBlock]).
 * An instance exists only through [parse], so a value always has four plain decimal octets, a
 * prefix from 0 to 32, and no host bits set beyond the prefix.  Strict parsing matters because
 * EC2 matches rules by their exact CIDR string: a loose value could be reported as "added" or
 * "not found" for a rule that EC2 stores under a different form.
 */
@JvmInline
value class Ipv4Cidr private constructor(
    val value: String,
) {
    /** The network address without the prefix, for example `198.51.100.0`. */
    val address: String
        get() = value.substringBefore('/')

    /** The prefix length, from 0 to 32. */
    val prefixLength: Int
        get() = value.substringAfter('/').toInt()

    override fun toString(): String = value

    companion object {
        private const val OCTET_COUNT = 4
        private const val MAX_OCTET = 255
        private const val MAX_PREFIX = 32
        private const val BITS_PER_OCTET = 8
        private const val ADDRESS_MASK = 0xFFFFFFFFL
        private const val OCTET_MASK = 0xFFL

        /** One to three ASCII digits with no leading zero; `0` alone is allowed. */
        private val DECIMAL = Regex("0|[1-9][0-9]{0,2}")

        /**
         * Parses [raw] as an IPv4 address or CIDR.
         *
         * A bare address means that address `/32`.  The value must be four decimal octets from 0 to
         * 255, with no sign, no leading zero and no whitespace, optionally followed by `/` and a
         * prefix from 0 to 32.  IPv6 is rejected.
         *
         * @return the canonical CIDR, or a failure holding an [InvalidCidrException] that names
         *   [raw] and the reason.  A value with host bits set fails, and its reason names the
         *   network form.
         */
        fun parse(raw: String): Result<Ipv4Cidr> {
            if (':' in raw) return invalid(raw, "IPv6 is not supported; give an IPv4 address or CIDR")
            val parts = raw.split('/')
            if (parts.size > 2) return invalid(raw, "a CIDR has at most one '/'")
            val octetTexts = parts[0].split('.')
            if (octetTexts.size != OCTET_COUNT) {
                return invalid(raw, "an IPv4 address has four octets separated by '.'")
            }
            val octets =
                octetTexts.map { text ->
                    decimal(text, MAX_OCTET) ?: return invalid(raw, "octet '$text' is not a number from 0 to 255")
                }
            val prefixText = parts.getOrNull(1)
            val prefix =
                when (prefixText) {
                    null -> MAX_PREFIX
                    else -> decimal(prefixText, MAX_PREFIX) ?: return invalid(raw, "prefix '$prefixText' is not a number from 0 to 32")
                }
            val address = octets.fold(0L) { acc, octet -> (acc shl BITS_PER_OCTET) or octet.toLong() }
            val network = address and networkMask(prefix)
            if (network != address) {
                return invalid(raw, "host bits are set beyond /$prefix; the network is ${format(network)}/$prefix")
            }
            return Result.success(Ipv4Cidr("${parts[0]}/$prefix"))
        }

        private fun decimal(
            text: String,
            max: Int,
        ): Int? = text.takeIf { DECIMAL.matches(it) }?.toInt()?.takeIf { it <= max }

        private fun networkMask(prefix: Int): Long =
            when (prefix) {
                0 -> 0L
                else -> (ADDRESS_MASK shl (MAX_PREFIX - prefix)) and ADDRESS_MASK
            }

        private fun format(address: Long): String =
            (OCTET_COUNT - 1 downTo 0).joinToString(".") { index ->
                ((address shr (index * BITS_PER_OCTET)) and OCTET_MASK).toString()
            }

        private fun invalid(
            raw: String,
            reason: String,
        ): Result<Ipv4Cidr> = Result.failure(InvalidCidrException(raw, reason))
    }
}

/**
 * The reason [Ipv4Cidr.parse] rejected a value.
 *
 * It carries the rejected [value] and the [reason] apart, so a caller can report every bad value
 * of a command in one structured event, and its message names both for a caller that only throws.
 */
class InvalidCidrException(
    val value: String,
    val reason: String,
) : IllegalArgumentException("Invalid CIDR '$value': $reason")
