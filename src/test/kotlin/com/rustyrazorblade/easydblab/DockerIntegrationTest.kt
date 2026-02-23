package com.rustyrazorblade.easydblab

import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.model.AccessMode
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
import org.junit.jupiter.api.assertThrows
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Integration tests for Docker class covering more complex scenarios.
 */
class DockerIntegrationTest {
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
    fun `exists returns false when image is missing`() {
        // Given
        whenever(mockDockerClient.listImages("test", "latest")).thenReturn(emptyList())

        // When
        val result = docker.exists("test", "latest")

        // Then
        assertFalse(result)
        verify(mockDockerClient).listImages("test", "latest")
    }

    @Test
    fun `exists returns true when image exists`() {
        // Given
        val mockImages = listOf(mock<com.github.dockerjava.api.model.Image>())
        whenever(mockDockerClient.listImages("test", "latest")).thenReturn(mockImages)

        // When
        val result = docker.exists("test", "latest")

        // Then
        assertTrue(result)
        verify(mockDockerClient).listImages("test", "latest")
    }

    @Test
    fun `addEnv validates blank environment variables`() {
        // When/Then
        assertThrows<IllegalArgumentException> {
            docker.addEnv("")
        }
    }

    @Test
    fun `addEnv adds multiple environment variables correctly`() {
        // When
        val result =
            docker
                .addEnv("VAR1=value1")
                .addEnv("VAR2=value2")
                .addEnv("VAR3=value3")

        // Then
        assertEquals(docker, result)

        // Set up mocks for container run
        whenever(mockContainerState.running).thenReturn(false)
        whenever(mockContainerState.exitCodeLong).thenReturn(0L)

        // Run container to verify env vars are passed
        docker.runContainer("test:latest", mutableListOf("echo"), "")

        // Verify env vars were set
        val envCaptor = argumentCaptor<Array<String>>()
        verify(mockContainerCreationCommand).withEnv(envCaptor.capture())
        val envArray = envCaptor.firstValue
        assertTrue(envArray.contains("VAR1=value1"))
        assertTrue(envArray.contains("VAR2=value2"))
        assertTrue(envArray.contains("VAR3=value3"))
    }

    @Test
    fun `runContainer validates empty command list`() {
        // When/Then
        assertThrows<IllegalArgumentException> {
            docker.runContainer("test:latest", mutableListOf(), "")
        }
    }

    @Test
    fun `runContainer validates blank image tag`() {
        // When/Then
        assertThrows<IllegalArgumentException> {
            docker.runContainer("", mutableListOf("echo"), "")
        }
    }

    @Test
    fun `runContainer with invalid user ID fails`() {
        // Given
        whenever(mockUserIdProvider.getUserId()).thenReturn(-1)

        // When/Then
        assertThrows<IllegalStateException> {
            docker.runContainer("test:latest", mutableListOf("echo"), "")
        }
    }

    @Test
    fun `runContainer with working directory sets it correctly`() {
        // Given
        whenever(mockContainerState.running).thenReturn(false)
        whenever(mockContainerState.exitCodeLong).thenReturn(0L)

        // When
        docker.runContainer("test:latest", mutableListOf("pwd"), "/workspace")

        // Then
        verify(mockContainerCreationCommand).withWorkingDir("/workspace")
        val hasExpectedMessage =
            bufferedOutputHandler.messages.any { msg ->
                msg.contains("Setting working directory inside container to /workspace")
            }
        assertTrue(hasExpectedMessage)
    }

    @Test
    fun `ContainerCreationCommand validates blank working directory`() {
        // Given
        val mockCreateCmd = mock<com.github.dockerjava.api.command.CreateContainerCmd>()
        val command = ContainerCreationCommand(mockCreateCmd)

        // When/Then
        assertThrows<IllegalArgumentException> {
            command.withWorkingDir("")
        }
    }

    @Test
    fun `VolumeMapping validates blank paths`() {
        // When/Then
        assertThrows<IllegalArgumentException> {
            VolumeMapping("", "/dest", AccessMode.rw)
        }

        assertThrows<IllegalArgumentException> {
            VolumeMapping("/source", "", AccessMode.rw)
        }
    }

    @Test
    fun `DefaultUserIdProvider returns valid user ID`() {
        // Given
        val provider = DefaultUserIdProvider()

        // When
        val userId = provider.getUserId()

        // Then
        assertTrue(userId >= 0)
    }
}
