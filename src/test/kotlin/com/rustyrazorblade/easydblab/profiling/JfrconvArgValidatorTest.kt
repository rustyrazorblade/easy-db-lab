package com.rustyrazorblade.easydblab.profiling

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tests for [JfrconvArgValidator] — the CLI-side guard for `jfrconv` passthrough arguments, where
 * the system supplies the input chunks and the output destination.
 */
class JfrconvArgValidatorTest {
    private val validator = JfrconvArgValidator()

    @ParameterizedTest
    @ValueSource(strings = ["-o", "--output", "-o=collapsed", "--output=collapsed"])
    fun `rejects the output-format argument`(arg: String) {
        assertThat(validator.validate(listOf(arg, "collapsed"))).isEqualTo(arg)
    }

    @Test
    fun `rejects a positional argument`() {
        assertThat(validator.validate(listOf("/tmp/mine.jfr"))).isEqualTo("/tmp/mine.jfr")
        assertThat(validator.validate(listOf("--include", "org.apache", "out.html"))).isEqualTo("out.html")
    }

    @Test
    fun `accepts a value that follows a flag`() {
        assertThat(validator.validate(listOf("--include", "org.apache.cassandra"))).isNull()
        assertThat(validator.validate(listOf("--from", "10:00:00"))).isNull()
    }

    @Test
    fun `accepts flags that take no value`() {
        assertThat(validator.validate(listOf("--threads", "--total"))).isNull()
    }

    @Test
    fun `accepts an empty argument list`() {
        assertThat(validator.validate(emptyList())).isNull()
    }

    @Test
    fun `rejection message names the argument`() {
        assertThat(validator.rejectionMessage("-o")).contains("-o")
    }
}
