package com.rustyrazorblade.easydblab.export

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * An object is downloaded as inclusive byte ranges (HTTP `Range: bytes=a-b`) of at most one part
 * each: together they cover every byte once, in order, with no gap and no overlap, whatever the size
 * against the part.
 */
class ByteRangesTest {
    @Test
    fun `an empty object has no range`() {
        assertThat(ByteRanges.split(0, 10)).isEmpty()
    }

    @Test
    fun `an object smaller than a part is one range`() {
        assertThat(ByteRanges.split(7, 10)).containsExactly(0L..6L)
    }

    @Test
    fun `an object of exactly one part is one range`() {
        assertThat(ByteRanges.split(10, 10)).containsExactly(0L..9L)
    }

    @Test
    fun `an object of a whole number of parts ends its last range on its last byte`() {
        assertThat(ByteRanges.split(30, 10)).containsExactly(0L..9L, 10L..19L, 20L..29L)
    }

    @Test
    fun `one byte past a part boundary is a last range of one byte`() {
        assertThat(ByteRanges.split(21, 10)).containsExactly(0L..9L, 10L..19L, 20L..20L)
    }

    @Test
    fun `a size over 2 GiB splits with no overflow`() {
        val size = 9_200_000_000L
        val part = 64L * 1024 * 1024

        val ranges = ByteRanges.split(size, part)

        assertThat(ranges.first()).isEqualTo(0L until part)
        assertThat(ranges.last().last).isEqualTo(size - 1)
        assertThat(ranges.zipWithNext()).allMatch { (a, b) -> b.first == a.last + 1 }
        assertThat(ranges.sumOf { it.last - it.first + 1 }).isEqualTo(size)
    }

    @Test
    fun `a part must hold at least one byte`() {
        assertThatThrownBy { ByteRanges.split(10, 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
