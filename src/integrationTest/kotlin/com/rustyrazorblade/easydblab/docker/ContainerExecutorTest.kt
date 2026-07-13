package com.rustyrazorblade.easydblab.docker

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.exception.DockerException
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.StreamType
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.DockerClientInterface
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.module.Module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

class ContainerExecutorTest :
    BaseKoinTest(),
    KoinComponent {
    private lateinit var mockDockerClient: DockerClientInterface
    private lateinit var bufferedOutputHandler: BufferedOutputHandler
    private lateinit var containerExecutor: ContainerExecutor

    @BeforeEach
    fun setup() {
        mockDockerClient = mock()
        // Get the OutputHandler from Koin and cast it to BufferedOutputHandler
        bufferedOutputHandler = get<OutputHandler>() as BufferedOutputHandler
        containerExecutor = ContainerExecutor(mockDockerClient)
    }

    @Test
    fun `startAndWaitForCompletion starts container and waits for completion`() {
        // Given
        val containerId = "test-container-123"
        val mockState = mock<InspectContainerResponse.ContainerState>()
        val mockInspectResponse = mock<InspectContainerResponse>()

        whenever(mockInspectResponse.state).thenReturn(mockState)
        whenever(mockDockerClient.inspectContainer(containerId)).thenReturn(mockInspectResponse)
        whenever(mockState.running).thenReturn(true).thenReturn(false)
        whenever(mockState.exitCodeLong).thenReturn(0L)

        // When
        val result = containerExecutor.startAndWaitForCompletion(containerId)

        // Then
        verify(mockDockerClient).startContainer(containerId)
        verify(mockDockerClient, atLeast(2)).inspectContainer(containerId)
        assertThat(result).isEqualTo(mockState)
        assertThat(bufferedOutputHandler.messages.any { it.contains("Starting container") }).isTrue()
    }

    @Test
    fun `startAndWaitForCompletion handles Docker exception`() {
        // Given
        val containerId = "test-container-123"
        whenever(mockDockerClient.startContainer(containerId))
            .thenThrow(DockerException("Connection refused", 500))

        // When/Then
        val exception =
            assertThrows<com.rustyrazorblade.easydblab.DockerException> {
                containerExecutor.startAndWaitForCompletion(containerId)
            }

        assertThat(exception.message).contains("Failed to start container")
        assertThat(bufferedOutputHandler.errors.any { it.first.contains("Error starting container") }).isTrue()
    }

    @Test
    fun `removeContainer removes container successfully`() {
        // Given
        val containerId = "test-container-123"

        // When
        containerExecutor.removeContainer(containerId)

        // Then
        verify(mockDockerClient).removeContainer(containerId, true)
    }

    @Test
    fun `removeContainer handles exceptions gracefully`() {
        // Given
        val containerId = "test-container-123"
        whenever(mockDockerClient.removeContainer(containerId, true))
            .thenThrow(RuntimeException("Container not found"))

        // When
        containerExecutor.removeContainer(containerId)

        // Then - should not throw, just log the error
        assertThat(bufferedOutputHandler.errors.any { it.first.contains("Failed to remove container") }).isTrue()
    }
}

class ContainerIOManagerTest :
    BaseKoinTest(),
    KoinComponent {
    private lateinit var mockDockerClient: DockerClientInterface
    private lateinit var bufferedOutputHandler: BufferedOutputHandler
    private lateinit var ioManager: ContainerIOManager

    @BeforeEach
    fun setup() {
        mockDockerClient = mock()
        // Get the OutputHandler from Koin and cast it to BufferedOutputHandler
        bufferedOutputHandler = get<OutputHandler>() as BufferedOutputHandler
        ioManager = ContainerIOManager(mockDockerClient)
    }

    @Test
    fun `attachToContainer attaches and counts frames correctly`() {
        // Given
        val containerId = "test-container-123"
        val callbackCaptor = argumentCaptor<ResultCallback.Adapter<Frame>>()

        doAnswer { invocation ->
            val callback = invocation.arguments[2] as ResultCallback.Adapter<Frame>
            // Simulate some frames
            callback.onNext(Frame(StreamType.STDOUT, "Line 1\n".toByteArray()))
            callback.onNext(Frame(StreamType.STDOUT, "Line 2\n".toByteArray()))
            callback.onNext(Frame(StreamType.STDERR, "Error line\n".toByteArray()))
            null
        }.whenever(mockDockerClient).attachContainer(eq(containerId), any(), callbackCaptor.capture())

        // When
        val framesRead = ioManager.attachToContainer(containerId, false)

        // Then
        assertThat(framesRead).isEqualTo(3)
        assertThat(bufferedOutputHandler.messages).contains("Attaching to running container")
        assertThat(bufferedOutputHandler.stdout).isEqualTo("Line 1\nLine 2\n")
        assertThat(bufferedOutputHandler.stderr).isEqualTo("Error line\n")
    }

    @Test
    fun `attachToContainer handles errors correctly`() {
        // Given
        val containerId = "test-container-123"

        doAnswer { invocation ->
            val callback = invocation.arguments[2] as ResultCallback.Adapter<Frame>
            callback.onError(IOException("Connection lost"))
            null
        }.whenever(mockDockerClient).attachContainer(eq(containerId), any(), any())

        // When
        val framesRead = ioManager.attachToContainer(containerId, false)

        // Then
        assertThat(framesRead).isEqualTo(0)
        assertThat(bufferedOutputHandler.errors.any { it.first.contains("Container attachment error") }).isTrue()
    }

    @Test
    fun `close cleans up resources`() {
        // When
        ioManager.close()

        // Then - should not throw any exceptions
        assertThat(bufferedOutputHandler.stdout).isEmpty()
    }
}

class ContainerStateMonitorTest :
    BaseKoinTest(),
    KoinComponent {
    private lateinit var mockDockerClient: DockerClientInterface
    private lateinit var bufferedOutputHandler: BufferedOutputHandler
    private lateinit var stateMonitor: ContainerStateMonitor

    override fun additionalTestModules(): List<Module> {
        // BufferedOutputHandler is already provided by testOutputModule() in coreTestModules()
        // We just need to get the instance for our tests
        return emptyList()
    }

    @BeforeEach
    fun setup() {
        mockDockerClient = mock()
        // Get the OutputHandler from Koin and cast it to BufferedOutputHandler
        bufferedOutputHandler = get<OutputHandler>() as BufferedOutputHandler
        stateMonitor = ContainerStateMonitor()
    }

    @Test
    fun `buildReturnMessage formats message correctly with exit code`() {
        // Given
        val mockState = mock<InspectContainerResponse.ContainerState>()
        whenever(mockState.exitCodeLong).thenReturn(0L)
        whenever(mockState.error).thenReturn(null)

        // When
        val message = stateMonitor.buildReturnMessage(mockState, 42)

        // Then
        assertThat(message).isEqualTo("Container exited with exit code 0, frames read: 42")
    }

    @Test
    fun `buildReturnMessage includes error when present`() {
        // Given
        val mockState = mock<InspectContainerResponse.ContainerState>()
        whenever(mockState.exitCodeLong).thenReturn(1L)
        whenever(mockState.error).thenReturn("Out of memory")

        // When
        val message = stateMonitor.buildReturnMessage(mockState, 10)

        // Then
        assertThat(message).isEqualTo("Container exited with exit code 1, Out of memory, frames read: 10")
    }

    @Test
    fun `reportFinalState logs message correctly`() {
        // Given
        val mockState = mock<InspectContainerResponse.ContainerState>()
        whenever(mockState.exitCodeLong).thenReturn(0L)
        whenever(mockState.error).thenReturn(null)

        // When
        stateMonitor.reportFinalState(mockState, 25)

        // Then
        assertThat(
            bufferedOutputHandler.messages.any {
                it.contains("Container exited with exit code 0") && it.contains("frames read: 25")
            },
        ).isTrue()
    }
}
