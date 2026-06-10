package com.rustyrazorblade.easydblab.mcp

import kotlinx.coroutines.channels.Channel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests the message sanitization logic in ChannelMessageBuffer in isolation —
 * no async machinery, no channel consumer, no timing dependencies.
 */
class CleanMessageTest {
    private val buffer = ChannelMessageBuffer(Channel(Channel.UNLIMITED))

    @Test
    fun `newlines are replaced with spaces`() {
        assertThat(buffer.cleanMessage("Line 1\nLine 2\r\nLine 3\rLine 4"))
            .isEqualTo("Line 1 Line 2 Line 3 Line 4")
    }

    @Test
    fun `multiple spaces are collapsed into single space`() {
        assertThat(buffer.cleanMessage("Word1    Word2     Word3"))
            .isEqualTo("Word1 Word2 Word3")
    }

    @Test
    fun `ANSI color codes are stripped`() {
        val esc = ""
        assertThat(buffer.cleanMessage("Use $esc[32measy-db-lab list$esc[39m to see versions"))
            .isEqualTo("Use easy-db-lab list to see versions")
    }

    @Test
    fun `complex ANSI sequences are stripped`() {
        val esc = ""
        assertThat(buffer.cleanMessage("$esc[1;33mYellow Bold$esc[0m normal $esc[31;1mRed Bold$esc[0m"))
            .isEqualTo("Yellow Bold normal Red Bold")
    }

    @Test
    fun `special characters and unicode are preserved`() {
        assertThat(buffer.cleanMessage("Message with \"quotes\", \n newlines, \t tabs, and unicode: 🎉"))
            .isEqualTo("Message with \"quotes\", newlines, tabs, and unicode: 🎉")
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertThat(buffer.cleanMessage("  hello world  "))
            .isEqualTo("hello world")
    }

    @Test
    fun `empty string is preserved`() {
        assertThat(buffer.cleanMessage("")).isEqualTo("")
    }
}
