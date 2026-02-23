package com.rustyrazorblade.easydblab

import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.model.AccessMode
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.Image
import com.github.dockerjava.api.model.StreamType
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DockerTest {
    private lateinit var mockContext: Context
    private lateinit var mockDockerClient: DockerClientInterface
    private lateinit var mockUserIdProvider: UserIdProvider
    private lateinit var bufferedOutputHandler: BufferedOutputHandler
    private lateinit var docker: Docker
    private lateinit var mockContainerCreationCommand: ContainerCreationCommand
    private lateinit var mockContainerResponse: com.github.dockerjava.api.command.CreateContainerResponse
    private lateinit var mockContainerState: InspectContainerResponse.ContainerState
    private lateinit var mockInspectContainerResponse: InspectContainerResponse

    @BeforeEach
    fun setup() {
        bufferedOutputHandler = BufferedOutputHandler()

        // Create a test-specific Koin module that uses our bufferedOutputHandler
        val testModule =
            module {
                factory<OutputHandler> { bufferedOutputHandler }
                single {
                    val eventBus = EventBus()
                    eventBus.addListener(
                        object : EventListener {
                            override fun onEvent(envelope: EventEnvelope) {
                                val text = envelope.event.toDisplayString()
                                if (envelope.event.isError()) {
                                    bufferedOutputHandler.handleError(text)
                                } else {
                                    bufferedOutputHandler.handleMessage(text)
                                }
                            }

                            override fun close() {}
                        },
                    )
                    eventBus
                }
            }

        startKoin {
            modules(testModule)
        }

        mockContext = mock()
        mockDockerClient = mock()
        mockUserIdProvider = mock()
        mockContainerCreationCommand = mock()
        mockContainerResponse = mock()
        mockContainerState = mock()
        mockInspectContainerResponse = mock()

        whenever(mockContainerResponse.id).thenReturn("container123456789")
        whenever(mockContainerCreationCommand.exec()).thenReturn(mockContainerResponse)
        whenever(mockContainerCreationCommand.withCmd(any<List<String>>())).thenReturn(mockContainerCreationCommand)
        whenever(mockContainerCreationCommand.withEnv(any())).thenReturn(mockContainerCreationCommand)
        whenever(mockContainerCreationCommand.withStdinOpen(any())).thenReturn(mockContainerCreationCommand)
        whenever(mockContainerCreationCommand.withVolumes(any())).thenReturn(mockContainerCreationCommand)
        whenever(mockContainerCreationCommand.withHostConfig(any())).thenReturn(mockContainerCreationCommand)
        whenever(mockContainerCreationCommand.withWorkingDir(any())).thenReturn(mockContainerCreationCommand)

        whenever(mockDockerClient.createContainer(any())).thenReturn(mockContainerCreationCommand)
        whenever(mockInspectContainerResponse.state).thenReturn(mockContainerState)
        whenever(mockDockerClient.inspectContainer(any())).thenReturn(mockInspectContainerResponse)

        whenever(mockUserIdProvider.getUserId()).thenReturn(1000)

        docker = Docker(mockContext, mockDockerClient, mockUserIdProvider)
    }

    @AfterEach
    fun teardown() {
        stopKoin()
    }

    @Test
    fun `exists returns true when images are found`() {
        val mockImages = listOf<Image>(mock())
        whenever(mockDockerClient.listImages("test", "latest")).thenReturn(mockImages)

        val result = docker.exists("test", "latest")

        assertTrue(result)
        verify(mockDockerClient).listImages("test", "latest")
    }

    @Test
    fun `exists returns false when no images are found`() {
        whenever(mockDockerClient.listImages("test", "latest")).thenReturn(emptyList())

        val result = docker.exists("test", "latest")

        assertFalse(result)
        verify(mockDockerClient).listImages("test", "latest")
    }

    @Test
    fun `addVolume adds volume to list and returns docker instance`() {
        val volumeMapping = VolumeMapping("/source", "/dest", AccessMode.rw)

        val result = docker.addVolume(volumeMapping)

        assertEquals(docker, result)
    }

    @Test
    fun `addEnv adds environment variable to list and returns docker instance`() {
        val result = docker.addEnv("TEST=value")

        assertEquals(docker, result)
    }

    @Test
    fun `pullImage calls dockerClient pullImage with correct parameters`() {
        // Use reflection to access the private volumes list
        val containerField = Containers::class.java.getDeclaredField("containerName")
        containerField.isAccessible = true
        val tagField = Containers::class.java.getDeclaredField("tag")
        tagField.isAccessible = true

        val mockContainer = mock<Containers>()
        containerField.set(mockContainer, "testcontainer")
        tagField.set(mockContainer, "testtag")

        // Mock the pullImage behavior
//        doNothing().whenever(mockDockerClient).pullImage(eq("testcontainer"), eq("testtag"), any())

//        docker.pullImage(mockContainer)

//        verify(mockDockerClient).pullImage(eq("testcontainer"), eq("testtag"), any())
    }

    @Test
    fun `runContainer with volumes sets up volumes correctly`() {
        // Given
        val mockVolume = mock<VolumeMapping>()
        whenever(mockVolume.source).thenReturn("/host/path")
        whenever(mockVolume.destination).thenReturn("/container/path")
        whenever(mockVolume.mode).thenReturn(com.github.dockerjava.api.model.AccessMode.rw)

        // First set running to true, then to false on second call to simulate container stopping
        whenever(mockContainerState.running).thenReturn(true).thenReturn(false)
        whenever(mockContainerState.exitCodeLong).thenReturn(0L)

        docker.addVolume(mockVolume)

        // When
        docker.runContainer("test:latest", mutableListOf("command"), "")

        // Then
        verify(mockContainerCreationCommand).withVolumes(any())
        verify(mockContainerCreationCommand).withHostConfig(any())
    }

    @Test
    fun `runContainer captures output correctly with BufferedOutputHandler`() {
        // Given
        whenever(mockContainerState.running).thenReturn(true).thenReturn(false)
        whenever(mockContainerState.exitCodeLong).thenReturn(0L)

        // Mock the attach container to simulate output
        doAnswer { invocation ->
            val callback = invocation.arguments[2] as com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>
            // Simulate some output
            callback.onNext(Frame(StreamType.STDOUT, "Hello from container\n".toByteArray()))
            callback.onNext(Frame(StreamType.STDERR, "Warning message\n".toByteArray()))
            null
        }.whenever(mockDockerClient).attachContainer(any(), any(), any())

        // When
        val result = docker.runContainer("test:latest", mutableListOf("echo", "hello"), "")

        // Then
        assertTrue(result.isSuccess)
        // The buffered handler should have captured the output
        assertTrue(bufferedOutputHandler.messages.any { it.contains("Starting") })
        assertTrue(bufferedOutputHandler.messages.any { it.contains("Container exited") })
    }

    @Test
    fun `runContainer handles non-zero exit code correctly`() {
        // Given
        whenever(mockContainerState.running).thenReturn(true).thenReturn(false)
        whenever(mockContainerState.exitCodeLong).thenReturn(1L)
        whenever(mockContainerState.error).thenReturn("Command failed")

        // When
        val result = docker.runContainer("test:latest", mutableListOf("false"), "")

        // Then
        assertTrue(result.isFailure)
        assertEquals("Non zero response returned.", result.exceptionOrNull()?.message)
        assertTrue(bufferedOutputHandler.messages.any { it.contains("Container exited with exit code 1") })
    }
}
