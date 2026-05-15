package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * Unit tests for DefaultProfilingService.buildSystemdRunCommand() — a pure command-building
 * function. Uses direct instantiation rather than Koin DI because no real runtime
 * dependencies are exercised.
 */
class BuildSystemdRunCommandTest {
    private val service =
        DefaultProfilingService(
            remoteOps = mock<RemoteOperationsService>(),
            eventBus = mock<EventBus>(),
        )

    @Test
    fun `no args or interval produces minimal command`() {
        val cmd = service.buildSystemdRunCommand(args = emptyList(), interval = null)
        assertThat(cmd).isEqualTo(
            "sudo systemd-run --unit=flamegraph-cassandra" +
                " -- /usr/local/bin/flamegraph-to-pyroscope",
        )
    }

    @Test
    fun `interval is passed as --interval flag`() {
        val cmd = service.buildSystemdRunCommand(args = emptyList(), interval = 30)
        assertThat(cmd).isEqualTo(
            "sudo systemd-run --unit=flamegraph-cassandra" +
                " -- /usr/local/bin/flamegraph-to-pyroscope --interval 30",
        )
    }

    @Test
    fun `asprof args are separated by double dash`() {
        val cmd = service.buildSystemdRunCommand(args = listOf("-e", "alloc"), interval = null)
        assertThat(cmd).isEqualTo(
            "sudo systemd-run --unit=flamegraph-cassandra" +
                " -- /usr/local/bin/flamegraph-to-pyroscope -- -e alloc",
        )
    }

    @Test
    fun `interval and args together`() {
        val cmd =
            service.buildSystemdRunCommand(
                args = listOf("-e", "cpu,alloc,lock"),
                interval = 5,
            )
        assertThat(cmd).isEqualTo(
            "sudo systemd-run --unit=flamegraph-cassandra" +
                " -- /usr/local/bin/flamegraph-to-pyroscope" +
                " --interval 5 -- -e cpu,alloc,lock",
        )
    }

    @Test
    fun `args with special characters are shell-quoted`() {
        val cmd =
            service.buildSystemdRunCommand(
                args = listOf("-e", "it's weird"),
                interval = null,
            )
        assertThat(cmd).contains("-- -e 'it'\\''s weird'")
    }
}
