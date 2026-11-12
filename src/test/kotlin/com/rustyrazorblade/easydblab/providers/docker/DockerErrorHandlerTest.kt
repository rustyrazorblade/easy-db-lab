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
    fun `a socket failure naming the docker socket is unavailable`() {
        val description = handler.describe(SocketException("/var/run/docker.sock: No such file or directory"))

        assertThat(description!!.kind).isEqualTo(ProviderErrorKind.UNAVAILABLE)
    }

    @Test
    fun `a file or bind-mount failure that names the docker socket is not claimed`() {
        // Mounting the socket into a container, or reading it as a file, is not a failure to reach the daemon.
        val bindMount =
            DockerException(
                "IO error starting container abc",
                IOException("error mounting \"/var/run/docker.sock\" to rootfs: not a directory"),
            )

        assertThat(handler.describe(IOException("cannot copy /var/run/docker.sock: Permission denied"))).isNull()
        assertThat(handler.describe(java.nio.file.NoSuchFileException("/home/me/.colima/docker.sock"))).isNull()
        assertThat(handler.describe(bindMount)).isNull()
    }

    @Test
    fun `a real docker-java connect failure to a missing socket is unavailable, detailed from the docker failure`() {
        // What docker-java's httpclient5 transport throws for a missing unix socket, wrapped as Docker.pullImage
        // wraps it, inside an unrelated outer exception the reporter would offer first.
        val dockerFailure =
            DockerException("IO error pulling image x:1", SocketException("No such file or directory"))
        val outer = IllegalStateException("setup failed", dockerFailure)

        val description = handler.describe(outer)

        assertThat(description!!.kind).isEqualTo(ProviderErrorKind.UNAVAILABLE)
        assertThat(description.detail).contains("IO error pulling image x:1").doesNotContain("setup failed")
    }

    @Test
    fun `a socket failure named after the docker endpoint is detailed from itself, not its wrapper`() {
        val outer = IllegalStateException("setup failed", SocketException("/var/run/docker.sock: Connection refused"))

        assertThat(handler.describe(outer)!!.detail).isEqualTo("/var/run/docker.sock: Connection refused")
    }

    @Test
    fun `a connection failure outside the docker failure does not tie it to docker`() {
        // The refused connection wraps the Docker answer, so it is not the reason Docker failed.
        val error = ConnectException("Connection refused").initCause(NotFoundException("No such image"))

        assertThat(handler.describe(error)).isNull()
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
