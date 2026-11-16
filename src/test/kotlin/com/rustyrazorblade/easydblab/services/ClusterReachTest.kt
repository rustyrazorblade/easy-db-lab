package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.client.KubernetesClientException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.NoSuchFileException
import java.util.stream.Stream

/**
 * What counts as "the step never reached the cluster". The line matters because a step that did
 * reach it (a timeout after the request left, an HTTP error) may have changed a workload, and
 * `down` must then keep reporting that it might have.
 */
class ClusterReachTest {
    companion object {
        @JvmStatic
        fun failures(): Stream<Arguments> =
            Stream.of(
                Arguments.of("refused connection", ConnectException("Connection refused"), true),
                Arguments.of("unknown host", UnknownHostException("10.0.1.5"), true),
                Arguments.of("no route", NoRouteToHostException("No route to host"), true),
                Arguments.of("SOCKS tunnel down", SocketException("Malformed reply from SOCKS server"), true),
                Arguments.of("missing kubeconfig", FileNotFoundException("kubeconfig (No such file or directory)"), true),
                Arguments.of("missing file", NoSuchFileException("/ws/kubeconfig"), true),
                Arguments.of(
                    "wrapped by the Kubernetes client",
                    KubernetesClientException("An error has occurred.", ConnectException("Connection refused")),
                    true,
                ),
                Arguments.of("read timeout", SocketTimeoutException("Read timed out"), false),
                Arguments.of("connection reset after sending", SocketException("Connection reset"), false),
                Arguments.of("plain I/O error", IOException("connection reset"), false),
                Arguments.of("API rejection", KubernetesClientException("forbidden"), false),
                Arguments.of("a step's own check", IllegalStateException("Loki's ingester did not finish flushing (status 503)"), false),
            )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    fun `a failure is classified by whether the request can have reached the cluster`(
        label: String,
        failure: Throwable,
        neverReached: Boolean,
    ) {
        assertThat(ClusterReach.neverReached(failure)).describedAs(label).isEqualTo(neverReached)
    }

    @Test
    fun `a cause loop does not hang the classifier`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        assertThat(ClusterReach.neverReached(a)).isFalse()
    }
}
