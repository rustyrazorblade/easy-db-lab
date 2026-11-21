package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration

/**
 * Tests for [KitEndpointHost]: an endpoint with a `pod-selector` is served on the node that runs a
 * ready pod matching it; an endpoint without one is served on every node of its node type.
 */
class KitEndpointHostTest {
    private val hosts =
        mapOf(
            ServerType.Stress to
                listOf(
                    ClusterHost(publicIp = "", privateIp = "10.0.1.10", alias = "app0", availabilityZone = "a"),
                    ClusterHost(publicIp = "", privateIp = "10.0.1.11", alias = "app1", availabilityZone = "a"),
                ),
        )

    private val plainEndpoint =
        KitEndpoint(name = "JDBC", nodeType = "app", port = 8080, type = KitEndpoint.EndpointType.JDBC, scheme = "presto")

    private val selectedEndpoint = plainEndpoint.copy(podSelector = "app=\${KIT_NAME},component=coordinator")

    /** Records the query it was asked, and answers with [answer]. */
    private class RecordingPods(
        private val answer: Result<List<KubernetesPod>>,
    ) : (String, String) -> Result<List<KubernetesPod>> {
        val queries = mutableListOf<Pair<String, String>>()

        override fun invoke(
            selector: String,
            namespace: String,
        ): Result<List<KubernetesPod>> {
            queries += selector to namespace
            return answer
        }
    }

    @Test
    fun `an endpoint with no pod selector is served on the first node of its node type and never asks k8s`() {
        val pods = RecordingPods(Result.success(emptyList()))

        val host = KitEndpointHost(pods).resolve(plainEndpoint, "presto", "default", hosts)

        assertThat(host.getOrThrow()).isEqualTo("10.0.1.10")
        assertThat(pods.queries).isEmpty()
    }

    @Test
    fun `an endpoint with a pod selector is served on the host of the ready pod, in the runtime namespace`() {
        val pods = RecordingPods(Result.success(listOf(pod("presto-coordinator-x", hostIp = "10.0.1.11"))))

        val host = KitEndpointHost(pods).resolve(selectedEndpoint, "presto", "analytics", hosts)

        assertThat(host.getOrThrow()).isEqualTo("10.0.1.11")
        assertThat(pods.queries).containsExactly("app=presto,component=coordinator" to "analytics")
    }

    @Test
    fun `no serving pod fails with a message naming the endpoint, selector and namespace`() {
        val pods = RecordingPods(Result.success(listOf(pod("presto-coordinator-x", hostIp = "10.0.1.11", ready = "0/1"))))

        val host = KitEndpointHost(pods).resolve(selectedEndpoint, "presto", "default", hosts)

        assertThat(host.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("JDBC")
            .hasMessageContaining("app=presto,component=coordinator")
            .hasMessageContaining("namespace default")
    }

    @Test
    fun `a failed pod query fails with its reason instead of falling back to a node that may not serve`() {
        val pods = RecordingPods(Result.failure(IllegalStateException("connection refused")))

        val host = KitEndpointHost(pods).resolve(selectedEndpoint, "presto", "default", hosts)

        assertThat(host.exceptionOrNull()).hasMessageContaining("connection refused")
    }

    @Test
    fun `no node of the endpoint node type fails`() {
        val host = KitEndpointHost(RecordingPods(Result.success(emptyList()))).resolve(plainEndpoint, "presto", "default", emptyMap())

        assertThat(host.exceptionOrNull()).hasMessageContaining("No app nodes")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("servingCases")
    fun `the serving host is the host of a running, ready pod that is not terminating`(
        case: String,
        pods: List<KubernetesPod>,
        expected: String?,
    ) {
        assertThat(KitEndpointHost.servingHostIp(pods)).`as`(case).isEqualTo(expected)
    }

    companion object {
        fun pod(
            name: String,
            hostIp: String,
            status: String = "Running",
            ready: String = "1/1",
            terminating: Boolean = false,
        ) = KubernetesPod(
            namespace = "default",
            name = name,
            status = status,
            ready = ready,
            restarts = 0,
            age = Duration.ZERO,
            terminating = terminating,
            hostIp = hostIp,
        )

        @JvmStatic
        fun servingCases(): List<Arguments> =
            listOf(
                Arguments.of("no pods", emptyList<KubernetesPod>(), null),
                Arguments.of("one ready pod", listOf(pod("a", "10.0.1.11")), "10.0.1.11"),
                Arguments.of("pending pod", listOf(pod("a", "10.0.1.11", status = "Pending", ready = "0/1")), null),
                Arguments.of("running but not ready", listOf(pod("a", "10.0.1.11", ready = "0/1")), null),
                Arguments.of("partly ready", listOf(pod("a", "10.0.1.11", ready = "1/2")), null),
                Arguments.of("terminating", listOf(pod("a", "10.0.1.11", terminating = true)), null),
                Arguments.of("unscheduled, no host ip", listOf(pod("a", "")), null),
                Arguments.of(
                    "old pod terminating, new pod ready elsewhere",
                    listOf(pod("a", "10.0.1.10", terminating = true), pod("b", "10.0.1.11")),
                    "10.0.1.11",
                ),
                Arguments.of(
                    "two ready pods pick the first by name",
                    listOf(pod("z", "10.0.1.10"), pod("b", "10.0.1.11")),
                    "10.0.1.11",
                ),
            )
    }
}
