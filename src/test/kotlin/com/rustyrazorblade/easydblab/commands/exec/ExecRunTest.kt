package com.rustyrazorblade.easydblab.commands.exec

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExecRunTest {
    private val execRun = ExecRun()

    @Test
    fun `deriveUnitName uses provided name when set`() {
        execRun.unitNameOverride = "my-watcher"
        val result = execRun.deriveUnitName("inotifywait -m /mnt/db1")
        assertThat(result).isEqualTo("my-watcher")
    }

    @Test
    fun `deriveUnitName extracts tool name from simple command`() {
        execRun.unitNameOverride = null
        val result = execRun.deriveUnitName("inotifywait -m /mnt/db1")
        assertThat(result).startsWith("inotifywait-")
        assertThat(result.substringAfter("inotifywait-")).matches("\\d+")
    }

    @Test
    fun `deriveUnitName extracts tool name from absolute path`() {
        execRun.unitNameOverride = null
        val result = execRun.deriveUnitName("/usr/bin/inotifywait -m /mnt/db1")
        assertThat(result).startsWith("inotifywait-")
    }

    @Test
    fun `deriveUnitName handles command with no arguments`() {
        execRun.unitNameOverride = null
        val result = execRun.deriveUnitName("htop")
        assertThat(result).startsWith("htop-")
    }

    @Test
    fun `buildSystemdRunCommand constructs foreground command with journal output`() {
        execRun.background = false
        val result =
            execRun.buildSystemdRunCommand(
                "edl-exec-test",
                "ls /mnt/db1",
            )
        assertThat(result).contains("--wait")
        assertThat(result).contains("--unit=edl-exec-test")
        assertThat(result).endsWith("-- ls /mnt/db1")
    }

    @Test
    fun `buildSystemdRunCommand constructs background command with journal output`() {
        execRun.background = true
        val result =
            execRun.buildSystemdRunCommand(
                "edl-exec-inotifywait",
                "inotifywait -m /mnt/db1",
            )
        assertThat(result).doesNotContain("--wait")
        assertThat(result).contains("--unit=edl-exec-inotifywait")
        assertThat(result).endsWith("-- inotifywait -m /mnt/db1")
    }

    @Test
    fun `buildSystemdRunCommand uses sudo`() {
        execRun.background = false
        val result =
            execRun.buildSystemdRunCommand(
                "edl-exec-test",
                "ls",
            )
        assertThat(result).startsWith("sudo systemd-run")
    }

    @Test
    fun `shellQuote wraps arguments with spaces in single quotes`() {
        with(execRun) {
            assertThat("%T %w%f %e".shellQuote()).isEqualTo("'%T %w%f %e'")
        }
    }

    @Test
    fun `shellQuote leaves safe arguments unquoted`() {
        with(execRun) {
            assertThat("inotifywait".shellQuote()).isEqualTo("inotifywait")
            assertThat("/mnt/db1/cassandra/import/".shellQuote()).isEqualTo("/mnt/db1/cassandra/import/")
            assertThat("--format".shellQuote()).isEqualTo("--format")
            assertThat("%Y-%m-%dT%H:%M:%S".shellQuote()).isEqualTo("%Y-%m-%dT%H:%M:%S")
        }
    }

    @Test
    fun `shellQuote escapes embedded single quotes`() {
        with(execRun) {
            assertThat("it's".shellQuote()).isEqualTo("'it'\\''s'")
        }
    }

    @Test
    fun `shellQuote handles empty string`() {
        with(execRun) {
            assertThat("".shellQuote()).isEqualTo("''")
        }
    }
}
