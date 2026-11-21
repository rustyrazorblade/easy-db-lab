package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.CiliumInstallAnnotator
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.KitStorageResolver
import com.rustyrazorblade.easydblab.services.ObservabilityStackService
import io.fabric8.kubernetes.client.KubernetesClientException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * A failed observability deploy during `up` must keep the Fabric8 failure as the cause, so a 403 on
 * a cluster without RBAC is described as a Kubernetes permission denial, not as rebuilt text.
 */
class UpPostStepsTest {
    @Test
    fun `an observability deploy failure keeps the Kubernetes exception as the cause`() {
        val denied = KubernetesClientException("forbidden", 403, null)
        val steps =
            UpPostSteps(
                KitStorageResolver(mock<K8sService>(), EventBus()),
                object : ObservabilityStackService {
                    override fun deploy(): Result<Unit> = Result.failure(denied)
                },
                mock<CiliumInstallAnnotator>(),
                EventBus(),
            )

        assertThatThrownBy { steps.run(localTelemetry = false, kitStorageClass = null) }
            .hasMessageContaining("Observability stack deployment failed")
            .hasRootCause(denied)
    }
}
