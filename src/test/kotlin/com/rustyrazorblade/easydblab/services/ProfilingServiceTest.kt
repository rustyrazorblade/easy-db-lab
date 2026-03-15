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
    private lateinit var profilingService: ProfilingService
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

    @Test
    fun `profileNode should emit Starting then Complete events on success`() {
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
            .thenReturn(successResponse)

        val result = profilingService.profileNode(testHost, listOf("-d", "30"))

        assertThat(result.isSuccess).isTrue()
        assertThat(emittedEvents).hasSize(2)
        assertThat(emittedEvents[0]).isInstanceOf(Event.Profiling.Starting::class.java)
        assertThat(emittedEvents[1]).isInstanceOf(Event.Profiling.Complete::class.java)
    }

    @Test
    fun `profileNode should emit Starting then Error events on failure`() {
        whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
            .thenThrow(RuntimeException("SSH connection refused"))

        val result = profilingService.profileNode(testHost, listOf("-d", "30"))

        assertThat(result.isFailure).isTrue()
        assertThat(emittedEvents).hasSize(2)
        assertThat(emittedEvents[0]).isInstanceOf(Event.Profiling.Starting::class.java)
        val errorEvent = emittedEvents[1] as Event.Profiling.Error
        assertThat(errorEvent.host).isEqualTo("db0")
        assertThat(errorEvent.message).contains("SSH connection refused")
    }

    @Test
    fun `profileNode should build command with shell-quoted args`() {
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
            .thenReturn(successResponse)

        profilingService.profileNode(testHost, listOf("-d", "30", "-e", "alloc"))

        verify(mockRemoteOps).executeRemotely(
            eq(testHost),
            eq("/usr/local/bin/flamegraph-to-pyroscope -d 30 -e alloc"),
            any(),
            any(),
        )
    }

    @Test
    fun `profileNode should shell-quote args containing special characters`() {
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
            .thenReturn(successResponse)

        // An arg with a space should be single-quoted
        profilingService.profileNode(testHost, listOf("-e", "cpu wall"))

        verify(mockRemoteOps).executeRemotely(
            eq(testHost),
            eq("/usr/local/bin/flamegraph-to-pyroscope -e 'cpu wall'"),
            any(),
            any(),
        )
    }

    @Test
    fun `profileNode with no args should execute bare command`() {
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
            .thenReturn(successResponse)

        profilingService.profileNode(testHost, emptyList())

        verify(mockRemoteOps).executeRemotely(
            eq(testHost),
            eq("/usr/local/bin/flamegraph-to-pyroscope"),
            any(),
            any(),
        )
    }

    @Test
    fun `Error event is marked as error`() {
        val errorEvent = Event.Profiling.Error("db0", "something went wrong")
        assertThat(errorEvent.isError()).isTrue()
    }
}
