package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ProfilingServiceTest : BaseKoinTest() {
    private lateinit var mockRemoteOps: RemoteOperationsService
    private lateinit var profilingService: DefaultProfilingService
    private val emittedEvents = mutableListOf<Event>()

    private val testHost =
        Host(
            public = "54.123.45.67",
            private = "10.0.1.5",
            alias = "db0",
            availabilityZone = "us-west-2a",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<RemoteOperationsService> { mockRemoteOps }
                factory<ProfilingService> { DefaultProfilingService(get(), get()) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockRemoteOps = mock()
        profilingService = getKoin().get<ProfilingService>() as DefaultProfilingService
        emittedEvents.clear()
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    emittedEvents.add(envelope.event)
                }

                override fun close() = Unit
            },
        )
    }

    @Nested
    inner class BuildSystemdRunCommand {
        @Test
        fun `no args or interval produces minimal command`() {
            val cmd = profilingService.buildSystemdRunCommand(emptyList(), null)
            assertThat(cmd).isEqualTo(
                "sudo systemd-run --unit=flamegraph-cassandra" +
                    " -- /usr/local/bin/flamegraph-to-pyroscope",
            )
        }

        @Test
        fun `interval is passed as --interval flag`() {
            val cmd = profilingService.buildSystemdRunCommand(emptyList(), interval = 30)
            assertThat(cmd).isEqualTo(
                "sudo systemd-run --unit=flamegraph-cassandra" +
                    " -- /usr/local/bin/flamegraph-to-pyroscope --interval 30",
            )
        }

        @Test
        fun `asprof args are separated by double dash`() {
            val cmd = profilingService.buildSystemdRunCommand(listOf("-e", "alloc"), null)
            assertThat(cmd).isEqualTo(
                "sudo systemd-run --unit=flamegraph-cassandra" +
                    " -- /usr/local/bin/flamegraph-to-pyroscope -- -e alloc",
            )
        }

        @Test
        fun `interval and args together`() {
            val cmd =
                profilingService.buildSystemdRunCommand(
                    listOf("-e", "cpu,alloc,lock"),
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
                profilingService.buildSystemdRunCommand(
                    listOf("-e", "it's weird"),
                    null,
                )
            assertThat(cmd).contains("-- -e 'it'\\''s weird'")
        }
    }

    @Nested
    inner class StartProfiling {
        @Test
        fun `emits Starting then Started events on success`() {
            val successResponse = Response(text = "", stderr = "")
            whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
                .thenReturn(successResponse)

            val result = profilingService.startProfiling(testHost, listOf("-e", "alloc"))

            assertThat(result.isSuccess).isTrue()
            assertThat(emittedEvents).hasSize(2)
            assertThat(emittedEvents[0]).isInstanceOf(Event.Profiling.Starting::class.java)
            assertThat(emittedEvents[1]).isInstanceOf(Event.Profiling.Started::class.java)
        }

        @Test
        fun `passes systemd-run command to remote ops`() {
            val successResponse = Response(text = "", stderr = "")
            whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
                .thenReturn(successResponse)

            profilingService.startProfiling(testHost, listOf("-e", "alloc"), interval = 5)

            val expectedCmd =
                "sudo systemd-run --unit=flamegraph-cassandra" +
                    " -- /usr/local/bin/flamegraph-to-pyroscope" +
                    " --interval 5 -- -e alloc"
            verify(mockRemoteOps).executeRemotely(
                eq(testHost),
                eq(expectedCmd),
                any(),
                any(),
            )
        }

        @Test
        fun `emits Starting then Error events on failure`() {
            whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
                .thenThrow(RuntimeException("SSH connection refused"))

            val result = profilingService.startProfiling(testHost, emptyList())

            assertThat(result.isFailure).isTrue()
            assertThat(emittedEvents).hasSize(2)
            assertThat(emittedEvents[0]).isInstanceOf(Event.Profiling.Starting::class.java)
            val errorEvent = emittedEvents[1] as Event.Profiling.Error
            assertThat(errorEvent.host).isEqualTo("db0")
            assertThat(errorEvent.message).contains("SSH connection refused")
        }
    }

    @Nested
    inner class Stop {
        @Test
        fun `emits Stopping then Stopped events on success`() {
            val successResponse = Response(text = "", stderr = "")
            whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
                .thenReturn(successResponse)

            val result = profilingService.stop(testHost)

            assertThat(result.isSuccess).isTrue()
            assertThat(emittedEvents).hasSize(2)
            assertThat(emittedEvents[0]).isInstanceOf(Event.Profiling.Stopping::class.java)
            assertThat(emittedEvents[1]).isInstanceOf(Event.Profiling.Stopped::class.java)
        }

        @Test
        fun `emits Stopping then Error events on failure`() {
            whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
                .thenThrow(RuntimeException("SSH timeout"))

            val result = profilingService.stop(testHost)

            assertThat(result.isFailure).isTrue()
            assertThat(emittedEvents).hasSize(2)
            assertThat(emittedEvents[0]).isInstanceOf(Event.Profiling.Stopping::class.java)
            val errorEvent = emittedEvents[1] as Event.Profiling.Error
            assertThat(errorEvent.host).isEqualTo("db0")
            assertThat(errorEvent.message).contains("SSH timeout")
        }
    }

    @Nested
    inner class IsRunning {
        @Test
        fun `returns true when service is active`() {
            val response = Response(text = "active", stderr = "")
            whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
                .thenReturn(response)

            val result = profilingService.isRunning(testHost)

            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow()).isTrue()
        }

        @Test
        fun `returns false when service is inactive`() {
            val response = Response(text = "inactive", stderr = "")
            whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
                .thenReturn(response)

            val result = profilingService.isRunning(testHost)

            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow()).isFalse()
        }
    }
}
