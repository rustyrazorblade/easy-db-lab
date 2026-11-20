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
 * A line ends at `\n`.  A `\r` starts a new segment that replaces the one before it, as a progress
 * bar redraws its line: at `\n` (or [close]) only the last non-empty segment is emitted, once, so
 * `50%\r100%\r\n` is one line, `100%`, and `\r\n` behaves as `\n`.
 *
 * A segment that reaches [maxLineBytes] with no line end is emitted in pieces, so output with no
 * newline cannot grow without bound, and no piece is longer than [maxLineBytes].  A piece is cut
 * after its last space or tab, so a word (and a credential URL, which holds no whitespace) shorter
 * than the cap is never split and can still be redacted whole.  With no whitespace, a URL that
 * starts within the last [Constants.NodeOutput.URL_CARRY_BYTES] bytes is carried whole to the next
 * piece; otherwise the piece is cut at the cap, before a character that does not fit.
 */
class LineSplittingOutputStream(
    private val maxLineBytes: Int = Constants.NodeOutput.MAX_LINE_BYTES,
    private val onLine: (String) -> Unit,
) : OutputStream() {
    private val buffer = ByteArrayOutputStream()

    /** The last non-empty segment a `\r` ended, emitted at the line end when no later one replaces it. */
    private var lastSegment: ByteArray? = null
    private var closed = false

    override fun write(b: Int) {
        val byte = b and BYTE_MASK
        when (byte) {
            LF -> endLine()
            CR -> endSegment()
            else -> append(byte)
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
        if (buffer.size() > 0 || lastSegment != null) endLine()
    }

    /**
     * Adds [byte] to the segment, first cutting a piece when the character it starts does not fit.
     * A continuation byte belongs to the character before it, which was checked whole; it is cut
     * only when the segment is already full, which only invalid UTF-8 reaches.
     */
    private fun append(byte: Int) {
        val needed = if (isContinuation(byte)) 1 else characterLength(byte)
        while (buffer.size() > 0 && buffer.size() + needed > maxLineBytes) {
            if (isContinuation(byte)) emitAll() else cut()
        }
        buffer.write(byte)
    }

    private fun endSegment() {
        if (buffer.size() > 0) lastSegment = buffer.toByteArray()
        buffer.reset()
    }

    private fun endLine() {
        val line = if (buffer.size() > 0) buffer.toByteArray() else lastSegment ?: ByteArray(0)
        buffer.reset()
        lastSegment = null
        onLine(String(line, Charsets.UTF_8))
    }

    /** Emits a full piece up to its last space or tab, or up to the URL that starts near its end, or all of it. */
    private fun cut() {
        val bytes = buffer.toByteArray()
        val whitespace = bytes.indexOfLast { it.toInt() == SPACE || it.toInt() == TAB }
        val end = if (whitespace >= 0) whitespace + 1 else urlStart(bytes)
        if (end <= 0) return emitAll()
        buffer.reset()
        buffer.write(bytes, end, bytes.size - end)
        onLine(String(bytes, 0, end, Charsets.UTF_8))
    }

    /** Where the scheme of the last `://` in the final [Constants.NodeOutput.URL_CARRY_BYTES] bytes starts, or -1. */
    private fun urlStart(bytes: ByteArray): Int {
        val from = maxOf(0, bytes.size - Constants.NodeOutput.URL_CARRY_BYTES)
        val separator = String(bytes, from, bytes.size - from, Charsets.ISO_8859_1).lastIndexOf("://")
        if (separator < 0) return -1
        var start = from + separator
        while (start > 0 && isSchemeByte(bytes[start - 1].toInt())) start--
        return start
    }

    private fun emitAll() {
        val line = buffer.toString(Charsets.UTF_8)
        buffer.reset()
        onLine(line)
    }

    private fun isContinuation(byte: Int): Boolean = byte and CONTINUATION_MASK == CONTINUATION_BITS

    /** The bytes of the UTF-8 character [lead] starts; 1 for an ASCII or invalid lead byte. */
    private fun characterLength(lead: Int): Int =
        when {
            lead and THREE_BYTE_MASK == TWO_BYTE_BITS -> TWO_BYTES
            lead and FOUR_BYTE_MASK == THREE_BYTE_BITS -> THREE_BYTES
            lead and FIVE_BYTE_MASK == FOUR_BYTE_BITS -> FOUR_BYTES
            else -> 1
        }

    private fun isSchemeByte(byte: Int): Boolean =
        byte.toChar().let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '+' || it == '.' || it == '-' }

    private companion object {
        const val LF = '\n'.code
        const val CR = '\r'.code
        const val SPACE = ' '.code
        const val TAB = '\t'.code
        const val BYTE_MASK = 0xFF
        const val CONTINUATION_MASK = 0xC0
        const val CONTINUATION_BITS = 0x80
        const val THREE_BYTE_MASK = 0xE0
        const val TWO_BYTE_BITS = 0xC0
        const val FOUR_BYTE_MASK = 0xF0
        const val THREE_BYTE_BITS = 0xE0
        const val FIVE_BYTE_MASK = 0xF8
        const val FOUR_BYTE_BITS = 0xF0
        const val TWO_BYTES = 2
        const val THREE_BYTES = 3
        const val FOUR_BYTES = 4
    }
}
