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
    fun `buildSystemdRunCommand constructs foreground command`() {
        execRun.background = false
        val result =
            execRun.buildSystemdRunCommand(
                "edl-exec-test",
                "/var/log/easydblab/tools/test.log",
                "ls /mnt/db1",
            )
        assertThat(result).contains("--wait")
        assertThat(result).contains("--unit=edl-exec-test")
        assertThat(result).contains("StandardOutput=file:/var/log/easydblab/tools/test.log")
        assertThat(result).contains("StandardError=file:/var/log/easydblab/tools/test.log")
        assertThat(result).endsWith("-- ls /mnt/db1")
    }

    @Test
    fun `buildSystemdRunCommand constructs background command`() {
        execRun.background = true
        val result =
            execRun.buildSystemdRunCommand(
                "edl-exec-inotifywait",
                "/var/log/easydblab/tools/inotifywait.log",
                "inotifywait -m /mnt/db1",
            )
        assertThat(result).doesNotContain("--wait")
        assertThat(result).contains("--unit=edl-exec-inotifywait")
        assertThat(result).contains("StandardOutput=file:/var/log/easydblab/tools/inotifywait.log")
        assertThat(result).endsWith("-- inotifywait -m /mnt/db1")
    }

    @Test
    fun `buildSystemdRunCommand uses sudo`() {
        execRun.background = false
        val result =
            execRun.buildSystemdRunCommand(
                "edl-exec-test",
                "/var/log/easydblab/tools/test.log",
                "ls",
            )
        assertThat(result).startsWith("sudo systemd-run")
    }
}
