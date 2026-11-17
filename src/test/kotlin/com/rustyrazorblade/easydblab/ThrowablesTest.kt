package com.rustyrazorblade.easydblab

import io.fabric8.kubernetes.client.KubernetesClientException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
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
        fun exceptions(): Stream<Exception> =
            Stream.of(IllegalStateException("state"), java.io.IOException("io"), RuntimeException("runtime"), Exception("checked"))

        @JvmStatic
        fun errors(): Stream<Error> = Stream.of(OutOfMemoryError("heap"), StackOverflowError(), AssertionError("assert"))

        @JvmStatic
        fun failures(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "client wrapper over a refused connection",
                    KubernetesClientException("An error has occurred.", ConnectException("Connection refused")),
                    "Connection refused",
                ),
                Arguments.of(
                    "generic wrappers nested twice",
                    KubernetesClientException(
                        "An error has occurred.",
                        KubernetesClientException("", ConnectException("No route")),
                    ),
                    "No route",
                ),
                Arguments.of(
                    "wrapper that names the operation keeps it before the cause",
                    KubernetesClientException("Operation: [delete] for kind: [Deployment] failed.", ConnectException("Connection refused")),
                    "Operation: [delete] for kind: [Deployment] failed.: Connection refused",
                ),
                Arguments.of(
                    "generic wrapper over a cause with no message",
                    KubernetesClientException("An error has occurred.", java.net.SocketTimeoutException()),
                    "java.net.SocketTimeoutException",
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

    @Test
    fun `a failure with no message reads as its class name, never as null`() {
        assertThat(IllegalStateException().messageOrString()).isEqualTo("java.lang.IllegalStateException")
        assertThat(IllegalStateException("boom").messageOrString()).isEqualTo("boom")
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("exceptions")
    fun `an exception becomes a failed result`(exception: Exception) {
        val result = runCatchingException { throw exception }

        assertThat(result.exceptionOrNull()).isSameAs(exception)
    }

    @Test
    fun `a value becomes a successful result`() {
        assertThat(runCatchingException { 42 }.getOrNull()).isEqualTo(42)
    }

    @Test
    fun `a captured interrupt leaves the thread's interrupt flag set`() {
        val result = runCatchingException { throw InterruptedException("stop") }

        try {
            assertThat(result.exceptionOrNull()).isInstanceOf(InterruptedException::class.java)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("errors")
    fun `an error in the JVM is rethrown, not captured`(error: Error) {
        assertThatThrownBy { runCatchingException { throw error } }.isSameAs(error)
    }
}
