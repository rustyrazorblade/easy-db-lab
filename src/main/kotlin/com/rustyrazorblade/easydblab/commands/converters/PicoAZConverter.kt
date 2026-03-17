package com.rustyrazorblade.easydblab.commands.converters

import picocli.CommandLine.ITypeConverter
import picocli.CommandLine.TypeConversionException

/**
 * PicoCLI converter for availability zone parameter values.
 *
 * Converts input strings to a list of single-letter availability zone identifiers.
 * Supports flexible input formats:
 * - Single letter suffix: "a" -> ["a"]
 * - Multiple suffixes (concatenated): "abc" -> ["a", "b", "c"]
 * - Full AZ names: "us-east-1c" -> ["c"]
 * - Comma or space separated: "us-east-1c, us-east-1d" -> ["c", "d"]
 * - Mixed: "a us-east-1b" -> ["a", "b"]
 *
 * Full AZ names must match the pattern: {geo}-{direction}-{number}{letter} (e.g., us-east-1a, eu-west-2b).
 * Tokens that are not full AZ names are parsed character by character for letter suffixes.
 */
class PicoAZConverter : ITypeConverter<List<String>> {
    companion object {
        // Matches full AZ names like us-east-1a, eu-west-2b, ap-southeast-1c
        private val FULL_AZ_PATTERN = Regex("[a-z]+-[a-z]+-\\d+([a-z])")
        private val LETTER_PATTERN = Regex("[a-z]")
    }

    override fun convert(value: String): List<String> {
        val tokens = value.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()

        return tokens.flatMap { token ->
            val fullAzMatch = FULL_AZ_PATTERN.matchEntire(token)
            if (fullAzMatch != null) {
                // Full AZ name like "us-east-1c" → extract the trailing letter
                listOf(fullAzMatch.groupValues[1])
            } else {
                // Single letter or concatenated letters like "a" or "abc" → extract all letters
                val letters = token.split("").filter { it.matches(LETTER_PATTERN) }
                if (letters.isEmpty()) {
                    throw TypeConversionException(
                        "Invalid AZ format: '$token'. Expected a letter suffix (e.g., 'a'), " +
                            "concatenated letters (e.g., 'abc'), or a full AZ name (e.g., 'us-east-1c').",
                    )
                }
                letters
            }
        }
    }
}
