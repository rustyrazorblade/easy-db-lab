package com.rustyrazorblade.easydblab.profiling

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tests for [AsprofArgValidator] — the CLI-side guard that rejects async-profiler arguments
 * easy-db-lab reserves for its own output plumbing, before any SSH round-trip.
 */
class AsprofArgValidatorTest {
    private val validator = AsprofArgValidator()

    @ParameterizedTest
    @ValueSource(strings = ["-f", "--file", "-o", "--output", "--loop", "-d", "--duration", "--timeout"])
    fun `rejects a reserved flag in bare CLI form`(flag: String) {
        assertThat(validator.validate(listOf("-e", "cpu", flag, "value"))).isEqualTo(flag)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "-f=/tmp/x.jfr",
            "--file=/tmp/x.jfr",
            "-o=collapsed",
            "--output=collapsed",
            "--loop=1h",
            "--timeout=30",
        ],
    )
    fun `rejects a reserved flag in equals form`(arg: String) {
        assertThat(validator.validate(listOf("-e", "cpu", arg))).isEqualTo(arg)
    }

    @ParameterizedTest
    @ValueSource(strings = ["file=/tmp/x.jfr", "loop=1h", "timeout=30", "flat=10", "traces=5"])
    fun `rejects a reserved option in agent form`(arg: String) {
        assertThat(validator.validate(listOf(arg))).isEqualTo(arg)
    }

    @ParameterizedTest
    @ValueSource(strings = ["cpu,file=/tmp/elsewhere.jfr", "10ms,loop=1h", "cpu,timeout=30", "cpu,collapsed"])
    fun `rejects a reserved option smuggled after a comma`(value: String) {
        assertThat(validator.validate(listOf("-e", value))).isEqualTo(value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["--nobatch", "nobatch"])
    fun `rejects the wall-clock batching switch in both spellings`(arg: String) {
        assertThat(validator.validate(listOf(arg))).isEqualTo(arg)
    }

    @Test
    fun `rejects the wall-clock batching switch appended to another flag`() {
        assertThat(validator.validate(listOf("-e", "wall", "--nobatch"))).isEqualTo("--nobatch")
    }

    @ParameterizedTest
    @ValueSource(strings = ["wall,nobatch", "nobatch,interval=10ms"])
    fun `rejects the wall-clock batching switch smuggled after a comma`(value: String) {
        assertThat(validator.validate(listOf("-e", value))).isEqualTo(value)
    }

    @Test
    fun `the batching switch is refused for corrupting the profile, not as output plumbing`() {
        // asprof 4.3 rejected --nobatch itself; 4.5 accepts it. The rejection has to explain the
        // corruption, because pointing someone at --loop describes neither the problem nor the fix.
        val message = validator.rejectionMessage("--nobatch")

        assertThat(message).contains("--nobatch")
        assertThat(message).contains("jdk.ExecutionSample")
        assertThat(message)
            .describedAs("--loop is irrelevant to an event-type change")
            .doesNotContain("--loop")
    }

    @Test
    fun `a comma-smuggled batching switch gets the corruption message, not the plumbing one`() {
        val message = validator.rejectionMessage("wall,nobatch")

        assertThat(message).contains("jdk.ExecutionSample")
        assertThat(message).doesNotContain("reserved by easy-db-lab")
    }

    @Test
    fun `rejects a bare output-format token in flag position`() {
        assertThat(validator.validate(listOf("collapsed"))).isEqualTo("collapsed")
        assertThat(validator.validate(listOf("-e", "cpu", "flamegraph"))).isEqualTo("flamegraph")
    }

    @Test
    fun `accepts an output-format word used as a flag value`() {
        assertThat(validator.validate(listOf("--include", "collapsed"))).isNull()
    }

    @Test
    fun `rejects an argument containing a newline`() {
        val arg = "cpu\nwall"
        assertThat(validator.validate(listOf("-e", arg))).isEqualTo(arg)
    }

    @Test
    fun `rejects an argument containing a NUL`() {
        val arg = "cpu\u0000wall"
        assertThat(validator.validate(listOf("-e", arg))).isEqualTo(arg)
    }

    @Test
    fun `accepts an argument containing spaces`() {
        assertThat(validator.validate(listOf("--include", "org.apache.cassandra spaced"))).isNull()
    }

    @Test
    fun `accepts profiling arguments that touch neither output nor rotation nor duration`() {
        val permitted =
            listOf(
                listOf("-e", "wall"),
                listOf("-i", "10ms"),
                listOf("--alloc", "2m"),
                listOf("--lock", "10ms"),
                // `vm`, not `dwarf`: async-profiler 4.4 made `dwarf` an alias for `vm` on HotSpot,
                // so listing it here would read as an endorsement of a flag whose meaning moved.
                listOf("--cstack", "vm"),
                listOf("--jfrsync", "profile"),
                listOf("--chunksize", "8m"),
                listOf("--include", "collapsed"),
            )
        permitted.forEach { args ->
            assertThat(validator.validate(args)).describedAs("args=%s", args).isNull()
        }
    }

    @Test
    fun `forwards an option it has never heard of`() {
        assertThat(validator.validate(listOf("--some-future-option", "17"))).isNull()
    }

    @Test
    fun `rejection message names the argument and points at the loop option`() {
        val message = validator.rejectionMessage("-f")
        assertThat(message).contains("-f")
        assertThat(message).contains("--loop")
    }

    @Test
    fun `a control character is refused for its own reason, not as a reserved flag`() {
        // A newline is rejected because the node reads arguments NUL-delimited and expands them as
        // argv without re-parsing — nothing to do with output plumbing. Telling someone their
        // newline "is reserved by easy-db-lab" and pointing them at --loop described neither the
        // problem nor anything they could act on.
        val message = validator.rejectionMessage("-e cpu\nwall")

        assertThat(message).contains("newline")
        assertThat(message).doesNotContain("reserved by easy-db-lab")
        assertThat(message)
            .describedAs("--loop cannot help with a control character")
            .doesNotContain("--loop")
    }
}
