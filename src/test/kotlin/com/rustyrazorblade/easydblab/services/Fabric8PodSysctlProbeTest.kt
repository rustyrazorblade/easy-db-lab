package com.rustyrazorblade.easydblab.services

import com.fasterxml.jackson.core.JsonParseException
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.tenancy.TestScopes
import com.rustyrazorblade.easydblab.kubernetes.KubeconfigNotFoundException
import com.rustyrazorblade.easydblab.kubernetes.ProxiedKubernetesClientFactory
import com.rustyrazorblade.easydblab.kubernetes.WorkspaceKubeconfig
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import java.util.stream.Stream

/**
 * How [Fabric8PodSysctlProbe] tells an API server that refused the probe pod from one it never
 * reached, using the exceptions fabric8 itself builds for each case.
 */
class Fabric8PodSysctlProbeTest {
    companion object {
        /** Each way fabric8 reports a request that got no answer: the cause is what the transport threw. */
        @JvmStatic
        fun transportFailures(): Stream<Arguments> =
            Stream.of(
                Arguments.of("connection refused", KubernetesClientException("create failed", ConnectException("Connection refused"))),
                Arguments.of("read timed out", KubernetesClientException("create failed", SocketTimeoutException("Read timed out"))),
                Arguments.of(
                    "fabric8 request timeout",
                    KubernetesClientException("create failed", IOException("timed out", TimeoutException())),
                ),
                Arguments.of("timeout with no IOException", KubernetesClientException("create failed", TimeoutException())),
            )
    }

    @Test
    fun `an answer with a status is a refusal carrying the API server's message`() {
        val status =
            StatusBuilder()
                .withCode(400)
                .withMessage("policy 'easydblab-pod-network-sysctls' denied the request")
                .build()

        assertThat(Fabric8PodSysctlProbe.resultOf(KubernetesClientException(status)))
            .isEqualTo(PodSysctlProbeResult.Refused("policy 'easydblab-pod-network-sysctls' denied the request"))
    }

    @Test
    fun `a workspace with no kubeconfig is unreachable, so up reports it through the typed event`(
        @TempDir workspace: File,
    ) {
        val provider =
            K8sClientProvider(
                WorkspaceKubeApiLocator(ClusterStateManager(File(workspace, "state.json")), workspace),
                ProxiedKubernetesClientFactory(),
            )

        val result = Fabric8PodSysctlProbe(provider) { TestScopes.EC2.namespace }.probe()

        assertThat(result).isInstanceOf(PodSysctlProbeResult.Unreachable::class.java)
        assertThat((result as PodSysctlProbeResult.Unreachable).reason).contains("kubeconfig not found")
    }

    @Test
    fun `a kubeconfig that does not parse is not unreachable, so up fails at once instead of waiting`(
        @TempDir workspace: File,
    ) {
        WorkspaceKubeconfig.file(workspace).apply { parentFile.mkdirs() }.writeText("{ this is not a kubeconfig")
        val provider =
            K8sClientProvider(
                WorkspaceKubeApiLocator(ClusterStateManager(File(workspace, "state.json")), workspace),
                ProxiedKubernetesClientFactory(),
            )

        assertThatThrownBy { Fabric8PodSysctlProbe(provider) { TestScopes.EC2.namespace }.probe() }
            .isInstanceOf(IllegalStateException::class.java)
            .isNotInstanceOf(KubeconfigNotFoundException::class.java)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transportFailures")
    fun `a request a transport failure ended is unreachable`(
        case: String,
        failure: KubernetesClientException,
    ) {
        assertThat(Fabric8PodSysctlProbe.resultOf(failure)).describedAs(case).isInstanceOf(PodSysctlProbeResult.Unreachable::class.java)
    }

    @Test
    fun `an answer the client could not parse is a defect, not an unreachable server`() {
        // fabric8 wraps a Jackson parse failure (an IOException subtype) the same way as a transport failure.
        val unreadable = KubernetesClientException("Error executing: POST", JsonParseException(null, "Unexpected character"))

        assertThatThrownBy { Fabric8PodSysctlProbe.resultOf(unreadable) }.isSameAs(unreadable)
    }

    @Test
    fun `an HTTP code without a status body is still a refusal`() {
        assertThat(Fabric8PodSysctlProbe.resultOf(KubernetesClientException("Forbidden", 403, null)))
            .isEqualTo(PodSysctlProbeResult.Refused("Forbidden"))
    }
}
