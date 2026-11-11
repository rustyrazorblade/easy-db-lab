package com.rustyrazorblade.easydblab.network

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.random.Random

class CidrBlockTest {
    @Test
    fun `accepts valid CIDR blocks with prefix 25 or smaller`() {
        listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "10.10.0.0/20", "10.0.0.0/25").forEach { cidrStr ->
            val cidr = CidrBlock(cidrStr)
            assertThat(cidr.value).isEqualTo(cidrStr)
        }
    }

    @Test
    fun `rejects CIDR with prefix larger than 25, which cannot hold eight 28 subnets`() {
        listOf("10.0.0.0/26", "10.0.0.0/28", "10.0.0.0/32").forEach { cidrStr ->
            assertThatThrownBy { CidrBlock(cidrStr) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("CIDR prefix must be /25 or larger")
                .hasMessageContaining(cidrStr.substringAfter('/'))
        }
    }

    @Test
    fun `rejects invalid CIDR formats`() {
        listOf(
            "invalid",
            "10.0.0.0",
            "10.0.0/16",
            "10.0.0.0.0/16",
            "10.0.0.256/16",
            "",
            "10.0.0.0/-1",
            "10.0.0.0/33",
        ).forEach { cidrStr ->
            assertThatThrownBy { CidrBlock(cidrStr) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `a bare address fails with an Invalid CIDR message naming it, not a number-format error`() {
        assertThatThrownBy { CidrBlock("10.0.0.0") }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid CIDR")
            .hasMessageContaining("10.0.0.0")
    }

    @Test
    fun `host bits set are rejected naming the network form`() {
        assertThatThrownBy { CidrBlock("10.0.0.5/16") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid CIDR")
            .hasMessageContaining("10.0.0.0/16")
    }

    @Test
    fun `a leading zero or sign is rejected`() {
        listOf("010.0.0.0/16", "+10.0.0.0/16", "10.0.0.0/016").forEach { cidrStr ->
            assertThatThrownBy { CidrBlock(cidrStr) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Invalid CIDR")
        }
    }

    @Test
    fun `prefixLength and networkAddress return correct values`() {
        val cidr = CidrBlock("172.31.0.0/16")
        assertThat(cidr.prefixLength).isEqualTo(16)
        assertThat(cidr.networkAddress).isEqualTo("172.31.0.0")
        assertThat(cidr.toString()).isEqualTo("172.31.0.0/16")
    }

    @Test
    fun `subnetCidr splits a 16 VPC into eight 19 subnets`() {
        val cidr = CidrBlock("10.0.0.0/16")
        assertThat((0..7).map { cidr.subnetCidr(it) })
            .containsExactly(
                "10.0.0.0/19",
                "10.0.32.0/19",
                "10.0.64.0/19",
                "10.0.96.0/19",
                "10.0.128.0/19",
                "10.0.160.0/19",
                "10.0.192.0/19",
                "10.0.224.0/19",
            )
    }

    @Test
    fun `subnetCidr carries into higher octets for other VPC sizes`() {
        assertThat(CidrBlock("172.16.0.0/12").subnetCidr(7)).isEqualTo("172.30.0.0/15")
        assertThat(CidrBlock("10.14.0.0/20").subnetCidr(1)).isEqualTo("10.14.2.0/23")
        assertThat(CidrBlock("192.168.5.0/24").subnetCidr(5)).isEqualTo("192.168.5.160/27")
        assertThat(CidrBlock("10.0.0.128/25").subnetCidr(7)).isEqualTo("10.0.0.240/28")
    }

    @Test
    fun `subnetCidr handles addresses with the high bit set`() {
        assertThat(CidrBlock("255.255.0.0/16").subnetCidr(7)).isEqualTo("255.255.224.0/19")
    }

    @Test
    fun `subnetCidr rejects a negative index`() {
        assertThatThrownBy { CidrBlock("10.0.0.0/16").subnetCidr(-1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `subnetCidr rejects a ninth availability zone with a message naming the limit`() {
        assertThatThrownBy { CidrBlock("10.0.0.0/16").subnetCidr(8) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at most 8 availability zones")
    }

    @Test
    fun `DEFAULT matches Constants and produces expected subnets`() {
        assertThat(CidrBlock.DEFAULT.value).isEqualTo(Constants.Vpc.DEFAULT_CIDR)
        assertThat(CidrBlock.DEFAULT.subnetCidr(0)).isEqualTo("10.0.0.0/19")
    }

    /** Every second octet 0–254 in use except [free]. */
    private fun allTakenExcept(vararg free: Int): List<String> = (0..254).filterNot { it in free }.map { "10.$it.0.0/16" }

    @Test
    fun `selectAvailable picks a random unused second octet, never a used one`() {
        val existing = listOf("10.0.0.0/16", "10.1.0.0/16")
        val selected = (0 until 200).map { seed -> CidrBlock.selectAvailable(existing, Random(seed)).value }

        assertThat(selected).allMatch { it.matches(Regex("""10\.\d+\.0\.0/16""")) }
        assertThat(selected).doesNotContain("10.0.0.0/16", "10.1.0.0/16")
        assertThat(selected.toSet()).hasSizeGreaterThan(1)
    }

    @Test
    fun `selectAvailable returns the only unused block when one remains`() {
        assertThat(CidrBlock.selectAvailable(allTakenExcept(7), Random(42)).value).isEqualTo("10.7.0.0/16")
    }

    @Test
    fun `selectAvailable ignores non-10-x VPC CIDRs`() {
        val existing = allTakenExcept(0) + listOf("172.16.0.0/16", "192.168.0.0/16")
        assertThat(CidrBlock.selectAvailable(existing, Random(42)).value).isEqualTo("10.0.0.0/16")
    }

    @Test
    fun `selectAvailable ignores CIDRs with non-integer second octet`() {
        val existing = allTakenExcept(0) + "10.abc.0.0/16"
        assertThat(CidrBlock.selectAvailable(existing, Random(42)).value).isEqualTo("10.0.0.0/16")
    }

    @Test
    fun `selectAvailable throws when all second octets 0-254 are taken`() {
        val existing = (0..254).map { "10.$it.0.0/16" }
        assertThatThrownBy { CidrBlock.selectAvailable(existing, Random(42)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No available CIDR blocks")
    }
}
