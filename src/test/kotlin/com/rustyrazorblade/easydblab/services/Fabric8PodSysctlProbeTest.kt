package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.kubernetes.ProxiedKubernetesClientFactory
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ConnectException

/**
 * How [Fabric8PodSysctlProbe] tells an API server that refused the probe pod from one it never
 * reached, using the exceptions fabric8 itself builds for each case.
 */
class Fabric8PodSysctlProbeTest {
    @Test
    fun `a request that got no answer is unreachable`() {
        val transport = KubernetesClientException("Operation: [create] for kind: [Pod] failed", ConnectException("Connection refused"))

        assertThat(Fabric8PodSysctlProbe.resultOf(transport)).isInstanceOf(ProbeResult.Unreachable::class.java)
    }

    @Test
    fun `an answer with a status is a refusal carrying the API server's message`() {
        val status =
            StatusBuilder()
                .withCode(400)
                .withMessage("policy 'easydblab-pod-network-sysctls' denied the request")
                .build()

        assertThat(Fabric8PodSysctlProbe.resultOf(KubernetesClientException(status)))
            .isEqualTo(ProbeResult.Refused("policy 'easydblab-pod-network-sysctls' denied the request"))
    }

    @Test
    fun `a workspace with no kubeconfig is unreachable, so up reports it through the typed event`(
        @TempDir workspace: File,
        @TempDir userDirectory: File,
    ) {
        val provider =
            K8sClientProvider(
                Context(easyDbLabUserDirectory = userDirectory, workingDirectory = workspace),
                ProxiedKubernetesClientFactory(),
            )

        val result = Fabric8PodSysctlProbe(provider).probe()

        assertThat(result).isInstanceOf(ProbeResult.Unreachable::class.java)
        assertThat((result as ProbeResult.Unreachable).reason).contains("kubeconfig not found")
    }

    @Test
    fun `an HTTP code without a status body is still a refusal`() {
        assertThat(Fabric8PodSysctlProbe.resultOf(KubernetesClientException("Forbidden", 403, null)))
            .isEqualTo(ProbeResult.Refused("Forbidden"))
    }
}
