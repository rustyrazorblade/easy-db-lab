package com.rustyrazorblade.easydblab.network

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class Ipv4CidrTest {
    @Test
    fun `a bare address means that address as a slash 32`() {
        assertThat(Ipv4Cidr.parse("203.0.113.7").getOrThrow().value).isEqualTo("203.0.113.7/32")
    }

    @ParameterizedTest
    @ValueSource(strings = ["0.0.0.0/0", "203.0.113.7/32", "198.51.100.0/24", "10.0.0.0/16", "255.255.255.255/32"])
    fun `a network-form CIDR is kept exactly`(raw: String) {
        assertThat(Ipv4Cidr.parse(raw).getOrThrow().value).isEqualTo(raw)
    }

    @Test
    fun `the prefix length and address are exposed`() {
        val cidr = Ipv4Cidr.parse("198.51.100.0/24").getOrThrow()

        assertThat(cidr.prefixLength).isEqualTo(24)
        assertThat(cidr.address).isEqualTo("198.51.100.0")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "300.1.1.1",
            "10.0.0.0/33",
            "10.0.0.0/-1",
            "foo",
            "+1.2.3.4/32",
            "1.+2.3.4/32",
            "01.2.3.4/32",
            "1.2.3.4/032",
            "1.2.3/24",
            "1.2.3.4.5/24",
            " 1.2.3.4/32",
            "1.2.3.4/32 ",
            "1.2.3.4 /32",
            "::1",
            "1.2.3.4/",
            "1.2.3.4/24/24",
            "",
            "1..3.4/32",
            "１.2.3.4/32",
        ],
    )
    fun `a malformed value is rejected with an error that names it`(raw: String) {
        val failure = Ipv4Cidr.parse(raw).exceptionOrNull()

        assertThat(failure).isInstanceOf(InvalidCidrException::class.java)
        val invalid = failure as InvalidCidrException
        assertThat(invalid.value).isEqualTo(raw)
        assertThat(invalid.reason).isNotBlank()
        assertThat(invalid.message).contains("'$raw'")
    }

    @Test
    fun `host bits set beyond the prefix are rejected naming the network form`() {
        val failure = Ipv4Cidr.parse("198.51.100.7/24").exceptionOrNull()

        assertThat(failure).isInstanceOf(InvalidCidrException::class.java)
        assertThat(failure?.message).contains("198.51.100.7/24").contains("198.51.100.0/24")
    }

    @Test
    fun `host bits in a slash 0 name the world network`() {
        assertThat(Ipv4Cidr.parse("1.2.3.4/0").exceptionOrNull()?.message).contains("0.0.0.0/0")
    }

    @Test
    fun `an out-of-range octet names the octet`() {
        assertThat(Ipv4Cidr.parse("300.1.1.1").exceptionOrNull()?.message).contains("300")
    }

    @Test
    fun `an IPv6 value is reported as unsupported`() {
        assertThat(Ipv4Cidr.parse("2001:db8::/32").exceptionOrNull()?.message).contains("IPv6")
    }
}
