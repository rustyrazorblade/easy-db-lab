package com.rustyrazorblade.easydblab.network

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.random.Random

class CidrBlockTest {
    @Test
    fun `accepts valid CIDR blocks with prefix 20 or smaller`() {
        listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "10.10.0.0/20").forEach { cidrStr ->
            val cidr = CidrBlock(cidrStr)
            assertThat(cidr.value).isEqualTo(cidrStr)
        }
    }

    @Test
    fun `rejects CIDR with prefix larger than 20`() {
        listOf("10.0.0.0/21", "10.0.0.0/24", "10.0.0.0/28").forEach { cidrStr ->
            assertThatThrownBy { CidrBlock(cidrStr) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("CIDR prefix must be /20 or larger")
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
    fun `prefixLength and networkAddress return correct values`() {
        val cidr = CidrBlock("172.31.0.0/16")
        assertThat(cidr.prefixLength).isEqualTo(16)
        assertThat(cidr.networkAddress).isEqualTo("172.31.0.0")
        assertThat(cidr.toString()).isEqualTo("172.31.0.0/16")
    }

    @Test
    fun `subnetCidr generates correct subnets for different VPC CIDRs`() {
        assertThat(CidrBlock("10.0.0.0/16").subnetCidr(0)).isEqualTo("10.0.1.0/24")
        assertThat(CidrBlock("10.0.0.0/16").subnetCidr(1)).isEqualTo("10.0.2.0/24")
        assertThat(CidrBlock("172.16.0.0/16").subnetCidr(0)).isEqualTo("172.16.1.0/24")
        assertThat(CidrBlock("192.168.0.0/16").subnetCidr(253)).isEqualTo("192.168.254.0/24")
    }

    @Test
    fun `subnetCidr rejects invalid indices`() {
        val cidr = CidrBlock("10.0.0.0/16")
        assertThatThrownBy { cidr.subnetCidr(-1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { cidr.subnetCidr(254) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `DEFAULT matches Constants and produces expected subnets`() {
        assertThat(CidrBlock.DEFAULT.value).isEqualTo(Constants.Vpc.DEFAULT_CIDR)
        assertThat(CidrBlock.DEFAULT.subnetCidr(0)).isEqualTo("10.0.1.0/24")
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
