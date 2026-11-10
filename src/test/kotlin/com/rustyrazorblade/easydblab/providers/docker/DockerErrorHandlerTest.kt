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
    fun `a refused connection to the daemon is unavailable with a check-docker remedy`() {
        val description = handler.describe(ConnectException("Connection refused"))

        assertThat(description).isNotNull
        assertThat(description!!.kind).isEqualTo(ProviderErrorKind.UNAVAILABLE)
        assertThat(description.provider).isEqualTo("Docker")
        assertThat(description.detail).contains("Connection refused")
        assertThat(description.remedy).contains("Docker is running")
    }

    @Test
    fun `a missing unix socket is unavailable`() {
        val description = handler.describe(IOException("/var/run/docker.sock: No such file or directory"))

        assertThat(description!!.kind).isEqualTo(ProviderErrorKind.UNAVAILABLE)
    }

    @Test
    fun `a socket failure is unavailable`() {
        assertThat(handler.describe(SocketException("Broken pipe"))!!.kind).isEqualTo(ProviderErrorKind.UNAVAILABLE)
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
