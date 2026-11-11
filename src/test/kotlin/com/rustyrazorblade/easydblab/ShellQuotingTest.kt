package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [shellQuote], the quoting used wherever a command has to be assembled as a single
 * string before being handed to a remote shell.
 */
class ShellQuotingTest {
    @Test
    fun `wraps arguments with spaces in single quotes`() {
        assertThat("%T %w%f %e".shellQuote()).isEqualTo("'%T %w%f %e'")
    }

    @Test
    fun `leaves safe arguments unquoted`() {
        assertThat("inotifywait".shellQuote()).isEqualTo("inotifywait")
        assertThat("/mnt/db1/cassandra/import/".shellQuote()).isEqualTo("/mnt/db1/cassandra/import/")
        assertThat("--format".shellQuote()).isEqualTo("--format")
        assertThat("%Y-%m-%dT%H:%M:%S".shellQuote()).isEqualTo("%Y-%m-%dT%H:%M:%S")
    }

    @Test
    fun `escapes embedded single quotes`() {
        assertThat("it's".shellQuote()).isEqualTo("'it'\\''s'")
    }

    @Test
    fun `handles empty string`() {
        assertThat("".shellQuote()).isEqualTo("''")
    }

    @Test
    fun `quotes shell metacharacters that would otherwise be interpreted`() {
        assertThat("\$(rm -rf /)".shellQuote()).isEqualTo("'\$(rm -rf /)'")
        assertThat("a;b".shellQuote()).isEqualTo("'a;b'")
        assertThat("a&&b".shellQuote()).isEqualTo("'a&&b'")
    }
}
