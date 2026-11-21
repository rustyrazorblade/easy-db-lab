package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.kernel.ProviderErrorHandler
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import io.fabric8.kubernetes.client.KubernetesClientException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication

/**
 * The Kubernetes handler is one of the provider handlers the reporter collects, and it resolves
 * from [kubernetesModule] alone, with no profile, workspace or cluster state bound, so it can
 * describe a failure during `init --kubeconfig`.
 */
class KubernetesErrorHandlerBindingTest {
    @Test
    fun `kubernetesModule registers a provider handler that claims a Kubernetes 401`() {
        val app = koinApplication { modules(kubernetesModule) }
        try {
            val kinds =
                app.koin
                    .getAll<ProviderErrorHandler>()
                    .mapNotNull { it.describe(KubernetesClientException("Unauthorized", 401, null))?.kind }

            assertThat(kinds).containsExactly(ProviderErrorKind.CREDENTIALS)
        } finally {
            app.close()
        }
    }
}
