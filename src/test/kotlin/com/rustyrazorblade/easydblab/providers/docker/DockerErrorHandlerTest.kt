package com.rustyrazorblade.easydblab.providers.docker

import com.github.dockerjava.api.exception.ConflictException
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.exception.NotModifiedException
import com.rustyrazorblade.easydblab.DockerException
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException

/**
 * Classification of Docker failures. Only an unreachable daemon gets the "check that Docker is
 * running" remedy; an API answer such as a 404 must keep its own message.
 */
class DockerErrorHandlerTest {
    private val handler = DockerErrorHandler()

    @Test
    fun `a connection failure inside a docker failure is unavailable with a check-docker remedy`() {
        val wrapped = DockerException("Error pulling image x:1", ConnectException("Connection refused"))

        val description = handler.describe(wrapped)

        assertThat(description).isNotNull
        assertThat(description!!.kind).isEqualTo(ProviderErrorKind.UNAVAILABLE)
        assertThat(description.provider).isEqualTo("Docker")
        assertThat(description.detail).contains("Error pulling image")
        assertThat(description.remedy).contains("Docker is running")
    }

    @Test
    fun `a docker-java failure caused by a socket failure is unavailable`() {
        val error =
            com.github.dockerjava.api.exception
                .DockerClientException("Could not connect", SocketException("Broken pipe"))

        assertThat(handler.describe(error)!!.kind).isEqualTo(ProviderErrorKind.UNAVAILABLE)
    }

    @Test
    fun `an IO failure naming the docker socket is unavailable`() {
        val description = handler.describe(IOException("/var/run/docker.sock: No such file or directory"))

        assertThat(description!!.kind).isEqualTo(ProviderErrorKind.UNAVAILABLE)
    }

    @Test
    fun `an IO failure naming the configured DOCKER_HOST is unavailable`() {
        val remote = DockerErrorHandler(dockerHost = "tcp://build-box:2375")

        assertThat(remote.describe(ConnectException("Connection refused: build-box/10.0.0.5:2375"))).isNotNull
        assertThat(remote.describe(ConnectException("Connection refused: other-box:2375"))).isNull()
    }

    @Test
    fun `a bare refused connection with no docker marker is not claimed`() {
        // A refused K3s API, SOCKS tunnel, Redis or SSH connection must not tell the user to check Docker.
        assertThat(handler.describe(ConnectException("Connection refused"))).isNull()
        assertThat(handler.describe(IOException("Connection refused: 127.0.0.1:6379"))).isNull()
    }

    @Test
    fun `a bare socket reset with no docker marker is not claimed`() {
        assertThat(handler.describe(SocketException("Connection reset"))).isNull()
    }

    @Test
    fun `an IO failure that is not a connection failure is not claimed`() {
        assertThat(handler.describe(IOException("disk full"))).isNull()
    }

    @Test
    fun `docker API answers keep their own message`() {
        assertThat(handler.describe(NotFoundException("No such container: abc"))).isNull()
        assertThat(handler.describe(ConflictException("name already in use"))).isNull()
        assertThat(handler.describe(NotModifiedException("already started"))).isNull()
    }

    @Test
    fun `the wrapper itself is not claimed, only a connection failure in its cause is`() {
        val wrapped = DockerException("Failed to start container abc", NotFoundException("No such container"))

        assertThat(handler.describe(wrapped)).isNull()
        assertThat(handler.describe(wrapped.cause!!)).isNull()
    }

    @Test
    fun `an unrelated exception is not claimed`() {
        assertThat(handler.describe(IllegalStateException("nope"))).isNull()
    }
}
