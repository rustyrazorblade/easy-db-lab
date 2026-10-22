package com.rustyrazorblade.easydblab.export

/**
 * Splits an object into the inclusive byte ranges (HTTP `Range: bytes=a-b`) that a parallel
 * download reads one each: together they cover every byte once, in order.
 */
object ByteRanges {
    /** The ranges of an object of [size] bytes, each at most [partBytes] long; none for an empty object. */
    fun split(
        size: Long,
        partBytes: Long,
    ): List<LongRange> {
        require(partBytes > 0) { "A part must hold at least one byte, was $partBytes" }
        require(size >= 0) { "An object's size cannot be negative, was $size" }
        return (0 until size step partBytes).map { start -> start..minOf(start + partBytes, size) - 1 }
    }
}
