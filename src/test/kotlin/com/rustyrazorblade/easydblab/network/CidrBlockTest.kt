package com.rustyrazorblade.easydblab.network

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

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
}
