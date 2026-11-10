package com.rustyrazorblade.easydblab.providers.docker

import com.rustyrazorblade.easydblab.DockerException
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Classification of Docker failures, so a stopped daemon keeps the "check that Docker is running"
 * guidance the old top-level catch gave.
 */
class DockerErrorHandlerTest {
    private val handler = DockerErrorHandler()

    @Test
    fun `a docker-java failure is unavailable with a check-docker remedy`() {
        val description =
            handler.describe(
                com.github.dockerjava.api.exception
                    .DockerException("no daemon", 500),
            )

        assertThat(description).isNotNull
        assertThat(description!!.kind).isEqualTo(ProviderErrorKind.UNAVAILABLE)
        assertThat(description.provider).isEqualTo("Docker")
        assertThat(description.remedy).contains("Docker is running")
    }

    @Test
    fun `the project's wrapped docker failure is claimed too`() {
        val description = handler.describe(DockerException("IO error pulling image", IOException("socket closed")))

        assertThat(description!!.kind).isEqualTo(ProviderErrorKind.UNAVAILABLE)
        assertThat(description.detail).contains("IO error pulling image")
    }

    @Test
    fun `an unrelated exception is not claimed`() {
        assertThat(handler.describe(IllegalStateException("nope"))).isNull()
    }
}
