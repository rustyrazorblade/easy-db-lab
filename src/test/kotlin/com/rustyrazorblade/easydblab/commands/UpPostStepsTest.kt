package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.services.CiliumInstallAnnotator
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.KitStorageResolver
import com.rustyrazorblade.easydblab.services.ObservabilityStackService
import com.rustyrazorblade.easydblab.services.PodNetworkTuning
import com.rustyrazorblade.easydblab.services.PodSysctlProbeResult
import com.rustyrazorblade.easydblab.services.RecordingManifests
import io.fabric8.kubernetes.client.KubernetesClientException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockingDetails
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * Tests for [UpPostSteps]: the pod network sysctl policy is a gate that comes first, so when it never
 * takes effect no kit StorageClass is checked and no observability pod or annotation is created with
 * the kernel defaults, and a failed observability deploy keeps the Fabric8 failure as its cause, so a
 * 403 on a cluster without RBAC is described as a Kubernetes permission denial, not as rebuilt text.
 */
class UpPostStepsTest {
    private val k8sService = mock<K8sService>()
    private val observability = mock<ObservabilityStackService>()
    private val annotator = mock<CiliumInstallAnnotator>()

    @Test
    fun `a pod network policy that never takes effect stops every later step`() {
        val eventBus = EventBus()
        val untuned =
            PodNetworkTuning(
                RecordingManifests(),
                { PodSysctlProbeResult.Admitted(emptyMap()) },
                eventBus,
                mapOf("net.core.somaxconn" to "65535"),
                Duration.ofMillis(50),
                Duration.ofMillis(10),
            )
        val steps = UpPostSteps(untuned, KitStorageResolver(k8sService, eventBus), observability, annotator, eventBus)

        assertThatThrownBy { steps.run(localTelemetry = true, kitStorageClass = "kits") }
            .isInstanceOf(CommandFailedException::class.java)
        verifyNoInteractions(k8sService, observability, annotator)
    }

    @Test
    fun `the pod network policy is in effect before the kit StorageClass check and the observability stack`() {
        val eventBus = EventBus()
        val sysctls = mapOf("net.core.somaxconn" to "65535")
        val manifests = RecordingManifests()
        val calls = mutableListOf<String>()
        val tuning =
            PodNetworkTuning(
                manifests,
                {
                    val later = mockingDetails(k8sService).invocations.size + mockingDetails(observability).invocations.size
                    calls += "probe after ${manifests.applied.size} applied, with $later later calls"
                    PodSysctlProbeResult.Admitted(sysctls)
                },
                eventBus,
                sysctls,
                Duration.ofMillis(50),
                Duration.ofMillis(10),
            )
        // A Result is a value class, so it is stubbed with thenReturn; Mockito records the call order.
        whenever(k8sService.storageClassReclaimPolicy("kits")).thenReturn(Result.success("Retain"))
        whenever(observability.deploy()).thenReturn(Result.success(Unit))

        UpPostSteps(tuning, KitStorageResolver(k8sService, eventBus), observability, annotator, eventBus)
            .run(localTelemetry = false, kitStorageClass = "kits")

        assertThat(calls).containsExactly("probe after 2 applied, with 0 later calls")
        inOrder(k8sService, observability) {
            verify(k8sService).storageClassReclaimPolicy("kits")
            verify(observability).deploy()
        }
    }

    @Test
    fun `an observability deploy failure keeps the Kubernetes exception as the cause`() {
        val eventBus = EventBus()
        val sysctls = mapOf("net.core.somaxconn" to "65535")
        val tuning =
            PodNetworkTuning(
                RecordingManifests(),
                { PodSysctlProbeResult.Admitted(sysctls) },
                eventBus,
                sysctls,
                Duration.ofMillis(50),
                Duration.ofMillis(10),
            )
        val denied = KubernetesClientException("forbidden", 403, null)
        whenever(observability.deploy()).thenReturn(Result.failure(denied))
        val steps = UpPostSteps(tuning, KitStorageResolver(k8sService, eventBus), observability, annotator, eventBus)

        assertThatThrownBy { steps.run(localTelemetry = false, kitStorageClass = null) }
            .hasMessageContaining("Observability stack deployment failed")
            .hasRootCause(denied)
    }
}
