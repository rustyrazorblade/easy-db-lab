package com.rustyrazorblade.easydblab.commands.converters

import picocli.CommandLine.ITypeConverter
import picocli.CommandLine.TypeConversionException
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * PicoCLI converter that parses a human `--time` string to epoch milliseconds.
 *
 * The `grafana annotate` command places a marker on the Grafana timeline at a point in time. Grafana
 * annotations use epoch milliseconds. This converter turns operator-friendly input into that value.
 *
 * Supported forms:
 * - "now" or an empty string: the current time.
 * - A relative offset from now: "-30m", "-2h", "-1d" (also "+" for the future).
 * - An ISO-8601 instant: "2026-01-01T12:00:00Z".
 * - A raw epoch-milliseconds integer: "1767225600000".
 *
 * The converter is deterministic per invocation; it reads the clock once when it converts.
 */
class PicoEpochMillisConverter : ITypeConverter<Long> {
    override fun convert(value: String): Long {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.equals("now", ignoreCase = true)) {
            return Instant.now().toEpochMilli()
        }
        parseRelativeOffset(trimmed)?.let { return it }
        parseEpochMillis(trimmed)?.let { return it }
        return parseInstant(trimmed)
    }

    private fun parseRelativeOffset(value: String): Long? {
        val match = RELATIVE_PATTERN.matchEntire(value) ?: return null
        val amount = match.groupValues[1].toLong()
        val unit = match.groupValues[2]
        val duration =
            when (unit) {
                "s" -> Duration.ofSeconds(amount)
                "m" -> Duration.ofMinutes(amount)
                "h" -> Duration.ofHours(amount)
                "d" -> Duration.ofDays(amount)
                else -> return null
            }
        return Instant.now().plus(duration).toEpochMilli()
    }

    private fun parseEpochMillis(value: String): Long? {
        if (!value.all { it.isDigit() }) return null
        return value.toLongOrNull()
    }

    private fun parseInstant(value: String): Long =
        try {
            Instant.parse(value).toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw TypeConversionException(
                "Cannot parse time '$value'. Use 'now', a relative offset like '-30m'/'-2h'/'-1d', " +
                    "an ISO-8601 instant like '2026-01-01T12:00:00Z', or epoch milliseconds.",
            ).initCause(e)
        }

    private companion object {
        /** A signed integer followed by a unit: s (seconds), m (minutes), h (hours), d (days). */
        val RELATIVE_PATTERN = Regex("^([+-]\\d+)([smhd])$")
    }
}
