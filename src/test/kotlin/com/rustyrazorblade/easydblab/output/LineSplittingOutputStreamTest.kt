package com.rustyrazorblade.easydblab.output

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LineSplittingOutputStreamTest {
    private val lines = mutableListOf<String>()

    private fun stream(maxLineBytes: Int = 8192) = LineSplittingOutputStream(maxLineBytes) { lines.add(it) }

    private fun LineSplittingOutputStream.write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `a line split across writes comes out whole`() {
        val out = stream()
        out.write("hel")
        out.write("lo\nwor")
        assertThat(lines).containsExactly("hello")
        out.write("ld\n")
        assertThat(lines).containsExactly("hello", "world")
    }

    @Test
    fun `a multi-byte character split across writes is not broken`() {
        val out = stream()
        val bytes = "café ✓\n".toByteArray(Charsets.UTF_8)
        bytes.forEach { out.write(byteArrayOf(it)) }
        assertThat(lines).containsExactly("café ✓")
    }

    @Test
    fun `crlf ends a line without a trailing carriage return`() {
        val out = stream()
        out.write("one\r\ntwo\r")
        out.write("\nthree\r\n")
        assertThat(lines).containsExactly("one", "two", "three")
    }

    @Test
    fun `a lone carriage return ends a line`() {
        val out = stream()
        out.write("10%\r20%\rdone\n")
        assertThat(lines).containsExactly("10%", "20%", "done")
    }

    @Test
    fun `an empty line is kept`() {
        val out = stream()
        out.write("a\n\nb\n")
        assertThat(lines).containsExactly("a", "", "b")
    }

    @Test
    fun `a line longer than the cap is emitted in pieces`() {
        val out = stream(maxLineBytes = 4)
        out.write("abcdefghij\n")
        assertThat(lines).containsExactly("abcd", "efgh", "ij")
    }

    @Test
    fun `a line longer than the cap is cut after its last space, so no word is split`() {
        // The URL (29 bytes) is shorter than the cap, so it stays whole and can be redacted.
        val out = stream(maxLineBytes = 32)
        out.write("cloning https://user:token@host/r.git now\n")
        assertThat(lines).containsExactly("cloning ", "https://user:token@host/r.git ", "now")
    }

    @Test
    fun `the cap never cuts inside a multi-byte character`() {
        val out = stream(maxLineBytes = 4)
        // "abc" + "é" (2 bytes) reaches 5 bytes; the cut waits for the character to finish.
        out.write("abcéd\n")
        assertThat(lines).containsExactly("abcé", "d")
    }

    @Test
    fun `close emits the partial last line once`() {
        val out = stream()
        out.write("first\nlast")
        out.close()
        out.close()
        assertThat(lines).containsExactly("first", "last")
    }

    @Test
    fun `close emits nothing after a complete line`() {
        val out = stream()
        out.write("only\n")
        out.close()
        assertThat(lines).containsExactly("only")
    }
}
