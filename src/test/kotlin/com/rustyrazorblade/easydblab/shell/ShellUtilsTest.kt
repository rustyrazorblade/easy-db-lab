package com.rustyrazorblade.easydblab.shell

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ShellUtilsTest {
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
    fun `passes through asprof event flags`() {
        assertThat("-e".shellQuote()).isEqualTo("-e")
        assertThat("cpu,alloc,lock".shellQuote()).isEqualTo("cpu,alloc,lock")
        assertThat("alloc".shellQuote()).isEqualTo("alloc")
    }

    @Test
    fun `passes through common asprof options`() {
        assertThat("--alloc".shellQuote()).isEqualTo("--alloc")
        assertThat("500k".shellQuote()).isEqualTo("500k")
        assertThat("10ms".shellQuote()).isEqualTo("10ms")
        assertThat("-t".shellQuote()).isEqualTo("-t")
    }
}
