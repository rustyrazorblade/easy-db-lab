package com.rustyrazorblade.easydblab.commands.converters

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import picocli.CommandLine.TypeConversionException
import java.time.Instant

/**
 * Tests for [PicoEpochMillisConverter], which parses operator `--time` input to epoch milliseconds.
 *
 * The converter has real parsing branches (now, relative offset, ISO instant, raw millis, and an
 * error path), so each branch is exercised for the value it produces or the failure it raises.
 */
class PicoEpochMillisConverterTest {
    private val converter = PicoEpochMillisConverter()

    @Test
    fun `empty string resolves to approximately now`() {
        val before = Instant.now().toEpochMilli()
        val result = converter.convert("")
        val after = Instant.now().toEpochMilli()
        assertThat(result).isBetween(before, after)
    }

    @Test
    fun `now keyword resolves to approximately now`() {
        val before = Instant.now().toEpochMilli()
        val result = converter.convert("now")
        val after = Instant.now().toEpochMilli()
        assertThat(result).isBetween(before, after)
    }

    @Test
    fun `raw epoch milliseconds pass through unchanged`() {
        assertThat(converter.convert("1767225600000")).isEqualTo(1767225600000L)
    }

    @Test
    fun `ISO-8601 instant converts to its epoch milliseconds`() {
        val expected = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()
        assertThat(converter.convert("2026-01-01T12:00:00Z")).isEqualTo(expected)
    }

    @Test
    fun `negative hour offset resolves before now`() {
        val now = Instant.now().toEpochMilli()
        val result = converter.convert("-2h")
        val twoHoursMs = 2 * 60 * 60 * 1000L
        assertThat(result).isLessThanOrEqualTo(now - twoHoursMs + TOLERANCE_MS)
        assertThat(result).isGreaterThanOrEqualTo(now - twoHoursMs - TOLERANCE_MS)
    }

    @Test
    fun `positive minute offset resolves after now`() {
        val now = Instant.now().toEpochMilli()
        val result = converter.convert("+30m")
        val thirtyMinutesMs = 30 * 60 * 1000L
        assertThat(result).isGreaterThanOrEqualTo(now + thirtyMinutesMs - TOLERANCE_MS)
    }

    @Test
    fun `unparseable value raises a conversion exception`() {
        assertThatThrownBy { converter.convert("yesterday") }
            .isInstanceOf(TypeConversionException::class.java)
            .hasMessageContaining("yesterday")
    }

    private companion object {
        const val TOLERANCE_MS = 5000L
    }
}
