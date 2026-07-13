package com.rustyrazorblade.easydblab

import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.model.AccessMode
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DockerIntegrationTest : BaseKoinTest() {
    val bufferedOutputHandler = BufferedOutputHandler()

    override fun coreTestModules(): List<Module> = emptyList()

    override fun additionalTestModules(): List<Module> =
        listOf(
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

                            override fun close() = Unit
                        },
                    )
                    eventBus
                }
            },
        )

    private lateinit var mockContext: Context
    private lateinit var mockDockerClient: DockerClientInterface
    private lateinit var mockUserIdProvider: UserIdProvider
    private lateinit var docker: Docker
    private lateinit var mockContainerCreationCommand: ContainerCreationCommand
    private lateinit var mockContainerResponse: com.github.dockerjava.api.command.CreateContainerResponse
    private lateinit var mockContainerState: InspectContainerResponse.ContainerState
    private lateinit var mockInspectContainerResponse: InspectContainerResponse

    @BeforeEach
    fun setup() {
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

    @Test
    fun `exists returns false when image is missing`() {
        whenever(mockDockerClient.listImages("test", "latest")).thenReturn(emptyList())

        assertThat(docker.exists("test", "latest")).isFalse()
        verify(mockDockerClient).listImages("test", "latest")
    }

    @Test
    fun `exists returns true when image exists`() {
        whenever(mockDockerClient.listImages("test", "latest")).thenReturn(listOf(mock()))

        assertThat(docker.exists("test", "latest")).isTrue()
        verify(mockDockerClient).listImages("test", "latest")
    }

    @Test
    fun `addEnv validates blank environment variables`() {
        assertThatThrownBy { docker.addEnv("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `addEnv adds multiple environment variables correctly`() {
        val result = docker.addEnv("VAR1=value1").addEnv("VAR2=value2").addEnv("VAR3=value3")
        assertThat(result).isEqualTo(docker)

        whenever(mockContainerState.running).thenReturn(false)
        whenever(mockContainerState.exitCodeLong).thenReturn(0L)
        docker.runContainer("test:latest", mutableListOf("echo"), "")

        val envCaptor = argumentCaptor<Array<String>>()
        verify(mockContainerCreationCommand).withEnv(envCaptor.capture())
        assertThat(envCaptor.firstValue).contains("VAR1=value1", "VAR2=value2", "VAR3=value3")
    }

    @Test
    fun `runContainer validates empty command list`() {
        assertThatThrownBy { docker.runContainer("test:latest", mutableListOf(), "") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `runContainer validates blank image tag`() {
        assertThatThrownBy { docker.runContainer("", mutableListOf("echo"), "") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `runContainer with invalid user ID fails`() {
        whenever(mockUserIdProvider.getUserId()).thenReturn(-1)

        assertThatThrownBy { docker.runContainer("test:latest", mutableListOf("echo"), "") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `runContainer with working directory sets it correctly`() {
        whenever(mockContainerState.running).thenReturn(false)
        whenever(mockContainerState.exitCodeLong).thenReturn(0L)

        docker.runContainer("test:latest", mutableListOf("pwd"), "/workspace")

        verify(mockContainerCreationCommand).withWorkingDir("/workspace")
        assertThat(bufferedOutputHandler.messages)
            .anyMatch { it.contains("Setting working directory inside container to /workspace") }
    }

    @Test
    fun `ContainerCreationCommand validates blank working directory`() {
        val mockCreateCmd = mock<com.github.dockerjava.api.command.CreateContainerCmd>()
        val command = ContainerCreationCommand(mockCreateCmd)

        assertThatThrownBy { command.withWorkingDir("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `VolumeMapping validates blank paths`() {
        assertThatThrownBy { VolumeMapping("", "/dest", AccessMode.rw) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { VolumeMapping("/source", "", AccessMode.rw) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `DefaultUserIdProvider returns valid user ID`() {
        assertThat(DefaultUserIdProvider().getUserId()).isGreaterThanOrEqualTo(0)
    }
}
