package com.rustyrazorblade.easydblab

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.CreateContainerResponse
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DockerTest : BaseKoinTest() {
    // Initialized eagerly so it is available when KoinTestExtension starts Koin
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
    private lateinit var mockContainerResponse: CreateContainerResponse
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
    fun `exists returns true when images are found`() {
        val mockImages = listOf<Image>(mock())
        whenever(mockDockerClient.listImages("test", "latest")).thenReturn(mockImages)

        assertThat(docker.exists("test", "latest")).isTrue()
        verify(mockDockerClient).listImages("test", "latest")
    }

    @Test
    fun `exists returns false when no images are found`() {
        whenever(mockDockerClient.listImages("test", "latest")).thenReturn(emptyList())

        assertThat(docker.exists("test", "latest")).isFalse()
        verify(mockDockerClient).listImages("test", "latest")
    }

    @Test
    fun `addVolume adds volume to list and returns docker instance`() {
        val volumeMapping = VolumeMapping("/source", "/dest", AccessMode.rw)

        assertThat(docker.addVolume(volumeMapping)).isEqualTo(docker)
    }

    @Test
    fun `addEnv adds environment variable to list and returns docker instance`() {
        assertThat(docker.addEnv("TEST=value")).isEqualTo(docker)
    }

    @Test
    fun `pullImage calls dockerClient pullImage with correct parameters`() {
        val containerField = Containers::class.java.getDeclaredField("containerName")
        containerField.isAccessible = true
        val tagField = Containers::class.java.getDeclaredField("tag")
        tagField.isAccessible = true

        val mockContainer = mock<Containers>()
        containerField.set(mockContainer, "testcontainer")
        tagField.set(mockContainer, "testtag")
    }

    @Test
    fun `runContainer with volumes sets up volumes correctly`() {
        val mockVolume = mock<VolumeMapping>()
        whenever(mockVolume.source).thenReturn("/host/path")
        whenever(mockVolume.destination).thenReturn("/container/path")
        whenever(mockVolume.mode).thenReturn(AccessMode.rw)

        whenever(mockContainerState.running).thenReturn(true).thenReturn(false)
        whenever(mockContainerState.exitCodeLong).thenReturn(0L)

        docker.addVolume(mockVolume)
        docker.runContainer("test:latest", mutableListOf("command"), "")

        verify(mockContainerCreationCommand).withVolumes(any())
        verify(mockContainerCreationCommand).withHostConfig(any())
    }

    @Test
    fun `runContainer captures output correctly with BufferedOutputHandler`() {
        whenever(mockContainerState.running).thenReturn(true).thenReturn(false)
        whenever(mockContainerState.exitCodeLong).thenReturn(0L)

        doAnswer { invocation ->
            val callback =
                invocation.getArgument<ResultCallback.Adapter<Frame>>(2)
            callback.onNext(Frame(StreamType.STDOUT, "Hello from container\n".toByteArray()))
            callback.onNext(Frame(StreamType.STDERR, "Warning message\n".toByteArray()))
            null
        }.whenever(mockDockerClient).attachContainer(any(), any(), any())

        val result = docker.runContainer("test:latest", mutableListOf("echo", "hello"), "")

        assertThat(result.isSuccess).isTrue()
        assertThat(bufferedOutputHandler.messages).anyMatch { it.contains("Starting") }
        assertThat(bufferedOutputHandler.messages).anyMatch { it.contains("Container exited") }
    }

    @Test
    fun `runContainer handles non-zero exit code correctly`() {
        whenever(mockContainerState.running).thenReturn(true).thenReturn(false)
        whenever(mockContainerState.exitCodeLong).thenReturn(1L)
        whenever(mockContainerState.error).thenReturn("Command failed")

        val result = docker.runContainer("test:latest", mutableListOf("false"), "")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).isEqualTo("Non zero response returned.")
        assertThat(bufferedOutputHandler.messages).anyMatch { it.contains("Container exited with exit code 1") }
    }
}
