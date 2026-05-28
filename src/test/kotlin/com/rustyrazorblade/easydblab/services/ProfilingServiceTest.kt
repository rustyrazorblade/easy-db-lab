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
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

class ProfilingServiceTest : BaseKoinTest() {
    private lateinit var mockRemoteOps: RemoteOperationsService
    private lateinit var profilingService: ProfilingService
    private val emittedEvents = mutableListOf<Event>()

    private val testHost =
        Host(
            public = "54.123.45.67",
            private = "10.0.1.5",
            alias = "db0",
            availabilityZone = "us-west-2a",
        )

    private val activeResponse = Response(text = "active", stderr = "")
    private val inactiveResponse = Response(text = "inactive", stderr = "")
    private val successResponse = Response(text = "", stderr = "")

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
        profilingService = getKoin().get()
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

    /**
     * Stub `systemctl is-active` to return the given response and every other
     * command to return success. Use this for tests that exercise the
     * startProfiling -> isRunning -> executeRemotely path.
     */
    private fun stubIsActive(isActive: Boolean) {
        whenever(
            mockRemoteOps.executeRemotely(
                eq(testHost),
                eq("sudo systemctl is-active flamegraph-cassandra"),
                any(),
                any(),
            ),
        ).thenReturn(if (isActive) activeResponse else inactiveResponse)
        whenever(
            mockRemoteOps.executeRemotely(
                eq(testHost),
                argThat { this != "sudo systemctl is-active flamegraph-cassandra" },
                any(),
                any(),
            ),
        ).thenReturn(successResponse)
    }

    @Nested
    inner class StartProfiling {
        @Test
        fun `emits Starting then Started events on success`() {
            stubIsActive(isActive = false)

            val result = profilingService.startProfiling(testHost, listOf("-e", "alloc"))

            assertThat(result.isSuccess).isTrue()
            assertThat(emittedEvents).hasSize(2)
            assertThat(emittedEvents[0]).isInstanceOf(Event.Profiling.Starting::class.java)
            assertThat(emittedEvents[1]).isInstanceOf(Event.Profiling.Started::class.java)
        }

        @Test
        fun `passes systemd-run command to remote ops`() {
            stubIsActive(isActive = false)

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
        fun `emits Starting then AlreadyRunning when unit is already active`() {
            stubIsActive(isActive = true)

            val result = profilingService.startProfiling(testHost, emptyList())

            assertThat(result.isSuccess).isTrue()
            assertThat(emittedEvents).hasSize(2)
            assertThat(emittedEvents[0]).isInstanceOf(Event.Profiling.Starting::class.java)
            assertThat(emittedEvents[1]).isInstanceOf(Event.Profiling.AlreadyRunning::class.java)
        }

        @Test
        fun `does not invoke systemd-run when unit is already active`() {
            stubIsActive(isActive = true)

            profilingService.startProfiling(testHost, listOf("-e", "alloc"), interval = 5)

            // Only the is-active check should have been called; never the systemd-run command.
            verify(mockRemoteOps).executeRemotely(
                eq(testHost),
                eq("sudo systemctl is-active flamegraph-cassandra"),
                any(),
                any(),
            )
            verifyNoMoreInteractions(mockRemoteOps)
        }

        @Test
        fun `emits Starting then Error events on SSH failure`() {
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
            whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
                .thenReturn(activeResponse)

            val result = profilingService.isRunning(testHost)

            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow()).isTrue()
        }

        @Test
        fun `returns false when service is inactive`() {
            whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
                .thenReturn(inactiveResponse)

            val result = profilingService.isRunning(testHost)

            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow()).isFalse()
        }
    }
}
