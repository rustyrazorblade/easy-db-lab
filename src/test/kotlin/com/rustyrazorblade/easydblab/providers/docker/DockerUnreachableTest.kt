package com.rustyrazorblade.easydblab.providers.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.exception.DockerClientException
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import com.rustyrazorblade.easydblab.DefaultDockerClient
import com.rustyrazorblade.easydblab.DockerException
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import com.rustyrazorblade.easydblab.providers.aws.AwsErrorHandler
import com.rustyrazorblade.easydblab.services.CommandFailureReporter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.io.IOException
import java.time.Duration

/**
 * A Docker daemon that cannot be reached, through the real docker-java client and transport: the
 * client is pointed at a unix socket that does not exist, which is what a stopped Docker Desktop or
 * colima leaves behind. docker-java throws that as a bare `RuntimeException` around a
 * `SocketException` with no path, so these tests pin that the client seam turns it into a Docker
 * failure the reporter tells the user about, with the check-Docker remedy.
 */
class DockerUnreachableTest {
    @TempDir
    lateinit var dir: File

    private val socket by lazy { File(dir, "missing.sock").absolutePath }
    private val host by lazy { "unix://$socket" }

    private fun client(): DefaultDockerClient {
        val config = DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost(host).build()
        val http =
            ApacheDockerHttpClient
                .Builder()
                .dockerHost(config.dockerHost)
                .connectionTimeout(Duration.ofSeconds(2))
                .responseTimeout(Duration.ofSeconds(2))
                .build()
        return DefaultDockerClient(DockerClientImpl.getInstance(config, http), config.dockerHost.toString())
    }

    private fun reportedEvents(error: Throwable): List<Event> {
        val emitted = mutableListOf<Event>()
        val bus =
            EventBus().also {
                it.addListener(
                    object : EventListener {
                        override fun onEvent(envelope: EventEnvelope) {
                            emitted.add(envelope.event)
                        }

                        override fun close() = Unit
                    },
                )
            }
        // The production registration order: the AWS handler, then the Docker handler with DOCKER_HOST unset.
        CommandFailureReporter(bus, listOf(AwsErrorHandler(), DockerErrorHandler(dockerHost = null))).report(error)
        return emitted
    }

    @Test
    fun `listing images against a missing socket fails as a Docker failure that names the socket`() {
        val error = catchThrowable { client().listImages("", "") }

        assertThat(error).isInstanceOf(DockerException::class.java).hasMessageContaining(socket)
    }

    @Test
    fun `a missing socket with DOCKER_HOST unset is reported as Docker unavailable, not an execution error`() {
        val error = catchThrowable { client().listImages("", "") }

        val event = reportedEvents(error).single()

        assertThat(event).isInstanceOf(Event.Command.ProviderError::class.java)
        event as Event.Command.ProviderError
        assertThat(event.provider).isEqualTo("Docker")
        assertThat(event.kind).isEqualTo(ProviderErrorKind.UNAVAILABLE)
        assertThat(event.detail).contains(socket)
        assertThat(event.remedy).contains("Docker is running")
    }

    @Test
    fun `a pull against a missing socket fails from its await as a Docker failure`() {
        // The pull streams on docker-java's own thread, so its failure surfaces only from the await.
        val error = catchThrowable { client().pullImage("busybox", "latest", PullImageResultCallback()) }

        assertThat(error).isInstanceOf(DockerException::class.java)
        assertThat((reportedEvents(error).single() as Event.Command.ProviderError).kind)
            .isEqualTo(ProviderErrorKind.UNAVAILABLE)
    }

    @Test
    fun `inspecting a container against a missing socket is a Docker failure`() {
        val error = catchThrowable { client().inspectContainer("abc") }

        assertThat(DockerErrorHandler(dockerHost = null).describe(error)!!.kind).isEqualTo(ProviderErrorKind.UNAVAILABLE)
    }

    @Test
    fun `a docker-java client failure around a local IOException passes through, not as the daemon`() {
        // DockerClientException (a RuntimeException subclass) around a local file problem, for
        // example a build context docker-java could not read: the daemon is not to blame.
        val local =
            DockerClientException(
                "Failed to read build context",
                IOException("Permission denied"),
            )
        val docker = mock<DockerClient>()
        whenever(docker.inspectContainerCmd("abc")).thenThrow(local)

        val error = catchThrowable { DefaultDockerClient(docker, host).inspectContainer("abc") }

        assertThat(error).isSameAs(local)
    }

    @Test
    fun `a unix DOCKER_HOST socket path named by a connection failure is unavailable`() {
        // colima's socket: a unix URI has no host, so the socket path is the marker.
        val colima = DockerErrorHandler(dockerHost = "unix:///Users/me/.colima/default/docker.sock")

        val named = java.net.SocketException("connect to /Users/me/.colima/default/docker.sock failed")

        assertThat(colima.describe(named)!!.kind).isEqualTo(ProviderErrorKind.UNAVAILABLE)
        assertThat(colima.describe(java.net.SocketException("connect to /tmp/other failed"))).isNull()
    }
}
