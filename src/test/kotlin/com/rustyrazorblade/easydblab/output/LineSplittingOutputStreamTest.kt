package com.rustyrazorblade.easydblab.output

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

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

    /** Progress output redraws one line with `\r`; only its final state is a line worth printing or keeping. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("carriageReturnCases")
    fun `a carriage return starts a segment that replaces the one before it`(
        name: String,
        input: String,
        expected: List<String>,
    ) {
        val out = stream()
        out.write(input)
        out.close()
        assertThat(lines).describedAs(name).containsExactlyElementsOf(expected)
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
    fun `a tab is a cut point too`() {
        val out = stream(maxLineBytes = 8)
        out.write("key\tvalue123\n")
        assertThat(lines).containsExactly("key\t", "value123")
    }

    @Test
    fun `a full line that ends in whitespace is emitted whole`() {
        val out = stream(maxLineBytes = 4)
        out.write("abc def\n")
        assertThat(lines).containsExactly("abc ", "def")
    }

    /** The token after the space is longer than the cap, so it is cut at the cap after the space. */
    @Test
    fun `whitespace only at the start leaves a short piece, then the long token is cut at the cap`() {
        val out = stream(maxLineBytes = 4)
        out.write(" abcdefg\n")
        assertThat(lines).containsExactly(" ", "abcd", "efg")
    }

    @Test
    fun `a character that does not fit under the cap starts the next piece`() {
        val out = stream(maxLineBytes = 4)
        // "abc" + "é" (2 bytes) would be 5 bytes, so "é" goes to the next piece whole.
        out.write("abcéd\n")
        assertThat(lines).containsExactly("abc", "éd")
        assertThat(lines).allMatch { it.toByteArray(Charsets.UTF_8).size <= 4 }
    }

    @Test
    fun `invalid utf-8 with a long run of continuation bytes is still cut at the cap`() {
        val out = stream(maxLineBytes = 4)
        out.write(ByteArray(10) { 0x80.toByte() } + byteArrayOf('\n'.code.toByte()))
        assertThat(lines).hasSize(3)
        assertThat(lines).allMatch { it.toByteArray(Charsets.UTF_8).size <= 4 * 3 }
        assertThat(lines.map { it.length }).containsExactly(4, 4, 2)
    }

    /** A hard cut through a URL would leave neither half redactable; the URL moves to the next piece whole. */
    @Test
    fun `a hard cut carries a url to the next piece so its credential can still be redacted`() {
        val out = stream(maxLineBytes = 16)
        out.write("x=https://u:p@h/r\n")
        assertThat(lines).containsExactly("x=", "https://u:p@h/r")
    }

    @Test
    fun `a url longer than the cap is cut at the cap`() {
        val out = stream(maxLineBytes = 8)
        out.write("https://u:p@host/r\n")
        assertThat(lines).containsExactly("https://", "u:p@host", "/r")
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

    private companion object {
        @JvmStatic
        fun carriageReturnCases(): List<Arguments> =
            listOf(
                Arguments.of("progress then done", "10%\r20%\rdone\n", listOf("done")),
                Arguments.of("redraws ending in crlf", "50%\r100%\r\n", listOf("100%")),
                Arguments.of("an empty segment does not replace", "a\r\rb\n", listOf("b")),
                Arguments.of("trailing carriage returns keep the last segment", "x\r\r\n", listOf("x")),
                Arguments.of("a segment left at close is emitted", "50%\r", listOf("50%")),
                Arguments.of("crlf behaves as lf", "one\r\ntwo\r\n", listOf("one", "two")),
                Arguments.of("a bare crlf is an empty line", "\r\n", listOf("")),
            )
    }
}
