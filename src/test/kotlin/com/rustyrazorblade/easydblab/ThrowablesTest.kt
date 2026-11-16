package com.rustyrazorblade.easydblab

import io.fabric8.kubernetes.client.KubernetesClientException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.ConnectException
import java.util.stream.Stream

/**
 * The reason an operator reads for a failed step: the cause behind the Kubernetes client's generic
 * wrapper, and the exception's own text for everything else.
 */
class ThrowablesTest {
    companion object {
        @JvmStatic
        fun failures(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "client wrapper over a refused connection",
                    KubernetesClientException("An error has occurred.", ConnectException("Connection refused")),
                    "Connection refused",
                ),
                Arguments.of(
                    "client wrapper nested twice",
                    KubernetesClientException("outer", KubernetesClientException("inner", ConnectException("No route"))),
                    "No route",
                ),
                Arguments.of("client exception with no cause", KubernetesClientException("forbidden"), "forbidden"),
                Arguments.of(
                    "other exception with a cause keeps its own text",
                    IllegalStateException("flush failed", ConnectException("x")),
                    "flush failed",
                ),
                Arguments.of("no message", IllegalStateException(), "java.lang.IllegalStateException"),
            )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    fun `the reason is the cause behind the Kubernetes client wrapper`(
        label: String,
        failure: Throwable,
        expected: String,
    ) {
        assertThat(failure.reason()).describedAs(label).isEqualTo(expected)
    }
}
