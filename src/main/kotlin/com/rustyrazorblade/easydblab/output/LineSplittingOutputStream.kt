package com.rustyrazorblade.easydblab.output

import com.rustyrazorblade.easydblab.Constants
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * An [OutputStream] that turns a byte stream into whole lines and hands each one to [onLine].
 *
 * Remote output arrives in arbitrary chunks: a chunk can end in the middle of a line or in the
 * middle of a multi-byte UTF-8 character.  This stream buffers bytes until a line ends and only
 * then decodes it, so a line is never split and a character is never broken (a `\n` or `\r` byte
 * never occurs inside a UTF-8 sequence).
 *
 * A line ends at `\n` (a `\r` just before it is dropped), at a lone `\r` (progress output), or once
 * it holds [maxLineBytes] bytes with no line end (cut at the next character boundary), so output
 * with no newline cannot grow without bound.
 * [close] emits the last partial line.
 */
class LineSplittingOutputStream(
    private val maxLineBytes: Int = Constants.NodeOutput.MAX_LINE_BYTES,
    private val onLine: (String) -> Unit,
) : OutputStream() {
    private val buffer = ByteArrayOutputStream()
    private var pendingCarriageReturn = false
    private var closed = false

    override fun write(b: Int) {
        val byte = b and BYTE_MASK
        if (pendingCarriageReturn) {
            pendingCarriageReturn = false
            emit()
            if (byte == LF) return
        }
        when (byte) {
            LF -> emit()
            CR -> pendingCarriageReturn = true
            else -> {
                // Cut a full line only at a character boundary: a continuation byte stays with
                // the character it belongs to, so the cap never breaks a multi-byte character.
                if (buffer.size() >= maxLineBytes && !isContinuation(byte)) emit()
                buffer.write(byte)
            }
        }
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        for (i in off until off + len) write(b[i].toInt())
    }

    override fun close() {
        if (closed) return
        closed = true
        if (pendingCarriageReturn || buffer.size() > 0) {
            pendingCarriageReturn = false
            emit()
        }
    }

    private fun emit() {
        val line = buffer.toString(Charsets.UTF_8)
        buffer.reset()
        onLine(line)
    }

    private fun isContinuation(byte: Int): Boolean = byte and CONTINUATION_MASK == CONTINUATION_BITS

    private companion object {
        const val LF = '\n'.code
        const val CR = '\r'.code
        const val BYTE_MASK = 0xFF
        const val CONTINUATION_MASK = 0xC0
        const val CONTINUATION_BITS = 0x80
    }
}
