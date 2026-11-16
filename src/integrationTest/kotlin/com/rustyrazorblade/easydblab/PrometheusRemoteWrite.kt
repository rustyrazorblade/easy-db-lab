package com.rustyrazorblade.easydblab

import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream

/**
 * Encodes a Prometheus remote-write request body for tests: a `prometheus.WriteRequest` protobuf,
 * framed as Snappy block format.
 *
 * No Snappy codec is on the classpath, and remote write only requires a valid Snappy block, not a
 * compressed one: the body is written as literal elements only, which every Snappy decoder reads.
 * This is test support, standing in for the collector's `prometheus_remote_write` exporter so a
 * backend test can write exact series without starting a collector.
 */
object PrometheusRemoteWrite {
    /** One series: its labels (including `__name__`) and one sample. */
    data class Series(
        val labels: Map<String, String>,
        val value: Double,
        val timestampMillis: Long,
    )

    private const val MAX_LITERAL_CHUNK = 65_536
    private const val SMALL_LITERAL_MAX = 60
    private const val TAG_LITERAL_1_BYTE = 60
    private const val TAG_LITERAL_2_BYTES = 61
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8
    private const val VARINT_MASK = 0x7F
    private const val VARINT_CONTINUE = 0x80
    private const val VARINT_SHIFT = 7

    /** The remote-write body for [series]: Snappy-framed protobuf. */
    fun body(series: List<Series>): ByteArray = snappyLiteral(writeRequest(series))

    private fun writeRequest(series: List<Series>): ByteArray =
        message { out ->
            series.forEach { out.writeByteArray(1, timeSeries(it)) }
        }

    private fun timeSeries(series: Series): ByteArray =
        message { out ->
            series.labels.toSortedMap().forEach { (name, value) ->
                out.writeByteArray(
                    1,
                    message { label ->
                        label.writeString(1, name)
                        label.writeString(2, value)
                    },
                )
            }
            out.writeByteArray(
                2,
                message { sample ->
                    sample.writeDouble(1, series.value)
                    sample.writeInt64(2, series.timestampMillis)
                },
            )
        }

    private fun message(write: (CodedOutputStream) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        val out = CodedOutputStream.newInstance(buffer)
        write(out)
        out.flush()
        return buffer.toByteArray()
    }

    /** Snappy block format with literal elements only: a varint length, then the bytes as-is. */
    private fun snappyLiteral(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, input.size)
        var offset = 0
        while (offset < input.size) {
            val length = minOf(MAX_LITERAL_CHUNK, input.size - offset)
            val n = length - 1
            when {
                n < SMALL_LITERAL_MAX -> out.write(n shl 2)
                n <= BYTE_MASK -> {
                    out.write(TAG_LITERAL_1_BYTE shl 2)
                    out.write(n)
                }
                else -> {
                    out.write(TAG_LITERAL_2_BYTES shl 2)
                    out.write(n and BYTE_MASK)
                    out.write((n shr BITS_PER_BYTE) and BYTE_MASK)
                }
            }
            out.write(input, offset, length)
            offset += length
        }
        return out.toByteArray()
    }

    private fun writeVarint(
        out: ByteArrayOutputStream,
        value: Int,
    ) {
        var v = value
        while (v and VARINT_MASK.inv() != 0) {
            out.write((v and VARINT_MASK) or VARINT_CONTINUE)
            v = v ushr VARINT_SHIFT
        }
        out.write(v)
    }
}
