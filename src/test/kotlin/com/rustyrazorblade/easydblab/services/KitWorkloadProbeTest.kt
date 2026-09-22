package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.kubernetes.KubernetesService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * The helm-backed branch of [KitWorkloadProbe]. helm runs on the control node over SSH, so
 * [HelmService] is stubbed at that boundary; the pod-backed branch runs against K3s in
 * `KitWorkloadProbeIntegrationTest`.
 */
class KitWorkloadProbeTest {
    private val helmService: HelmService = mock()
    private val kubeService: KubernetesService = mock()
    private val probe = KitWorkloadProbe(kubeService, helmService)
    private val controlHost = ClusterHost(publicIp = "", privateIp = "10.0.0.1", alias = "control0", availabilityZone = "us-west-2a")

    @Test
    fun `a helm runtime is present when its release exists in the runtime namespace`() {
        whenever(helmService.releaseExists(any(), eq("trino"), eq("analytics"))).thenReturn(true)
        val runtime = KitRuntime(type = KitRuntime.RuntimeType.HELM, release = "trino", namespace = "analytics")

        assertThat(probe.find("trino", runtime, controlHost).getOrThrow())
            .isEqualTo(WorkloadPresence.Present(namespace = "analytics", resources = listOf("helm-release/trino")))
        verifyNoInteractions(kubeService)
    }

    @Test
    fun `a helm runtime without a release name is looked up by the kit name`() {
        whenever(helmService.releaseExists(any(), eq("presto"), eq("default"))).thenReturn(true)
        val runtime = KitRuntime(type = KitRuntime.RuntimeType.HELM)

        assertThat(probe.find("presto", runtime, controlHost).getOrThrow())
            .isEqualTo(WorkloadPresence.Present(namespace = "default", resources = listOf("helm-release/presto")))
    }

    @Test
    fun `a failed helm lookup fails the result instead of reporting the kit absent`() {
        whenever(helmService.releaseExists(any(), any(), any())).thenThrow(IllegalStateException("ssh down"))
        val runtime = KitRuntime(type = KitRuntime.RuntimeType.HELM, release = "trino")

        assertThat(probe.find("trino", runtime, controlHost).isFailure).isTrue()
    }
}
