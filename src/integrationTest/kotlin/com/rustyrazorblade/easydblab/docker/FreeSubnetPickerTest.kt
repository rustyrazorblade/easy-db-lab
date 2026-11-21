package com.rustyrazorblade.easydblab.docker

import com.rustyrazorblade.easydblab.network.Ipv4Cidr
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [FreeSubnetPicker] is pure: these tests need no Docker. They pin that it finds a free block
 * past networks that cover whole ranges, and fails only when every private block is taken.
 */
class FreeSubnetPickerTest {
    private fun cidr(value: String) = Ipv4Cidr.parse(value).getOrThrow()

    @Test
    fun `the first block of the first range is picked when nothing is taken`() {
        assertThat(FreeSubnetPicker.pick(taken = emptyList(), prefix = 28)).isEqualTo(cidr("172.16.0.0/28"))
    }

    @Test
    fun `a network that owns a whole slice is skipped past, like the htap network on 172 30`() {
        val taken = listOf(cidr("172.16.0.0/13"), cidr("172.24.0.0/14"), cidr("172.28.0.0/15"), cidr("172.30.0.0/16"))

        assertThat(FreeSubnetPicker.pick(taken, prefix = 28)).isEqualTo(cidr("172.31.0.0/28"))
    }

    @Test
    fun `a small network inside a block makes the picker move to the next block`() {
        val taken = listOf(cidr("172.16.0.4/30"))

        assertThat(FreeSubnetPicker.pick(taken, prefix = 28)).isEqualTo(cidr("172.16.0.16/28"))
    }

    @Test
    fun `when all of 172 16 slash 12 is taken the search moves on to 10 slash 8`() {
        val taken = listOf(cidr("172.16.0.0/12"), cidr("10.0.0.0/9"))

        assertThat(FreeSubnetPicker.pick(taken, prefix = 28)).isEqualTo(cidr("10.128.0.0/28"))
    }

    @Test
    fun `a network wider than a range, such as 0 0 0 0 slash 0, leaves no block`() {
        assertThat(FreeSubnetPicker.pick(listOf(cidr("0.0.0.0/0")), prefix = 28)).isNull()
    }

    @Test
    fun `the picker fails only when every private block is taken`() {
        val allPrivate = FreeSubnetPicker.PRIVATE_RANGES

        assertThat(FreeSubnetPicker.pick(allPrivate, prefix = 28)).isNull()
        assertThat(FreeSubnetPicker.pick(allPrivate.dropLast(1), prefix = 28)).isEqualTo(cidr("192.168.0.0/28"))
    }

    @Test
    fun `the picked block overlaps none of the taken networks`() {
        val taken = (0 until 200).map { cidr("172.16.$it.0/24") } + cidr("172.16.200.0/28")

        val picked = FreeSubnetPicker.pick(taken, prefix = 28)

        assertThat(picked).isEqualTo(cidr("172.16.200.16/28"))
        assertThat(taken).noneMatch { it.overlaps(requireNotNull(picked)) }
    }
}
