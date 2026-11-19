package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.services.CiliumInstallAnnotator
import com.rustyrazorblade.easydblab.services.K8sStorageOperations
import com.rustyrazorblade.easydblab.services.ObservabilityStackService
import com.rustyrazorblade.easydblab.services.PodNetworkTuning
import com.rustyrazorblade.easydblab.services.ProbeResult
import com.rustyrazorblade.easydblab.services.RecordingManifests
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * Tests for [UpPostSteps]: the pod network sysctl policy is a gate that comes first, so when it never
 * takes effect no StorageClass, observability pod or annotation is created with the kernel defaults.
 */
class UpPostStepsTest {
    private val storage = mock<K8sStorageOperations>()
    private val observability = mock<ObservabilityStackService>()
    private val annotator = mock<CiliumInstallAnnotator>()
    private val control =
        ClusterHost(publicIp = "54.1.1.1", privateIp = "10.0.0.1", alias = "control0", availabilityZone = "us-west-2a", instanceId = "i-c0")

    @Test
    fun `a pod network policy that never takes effect stops every later step`() {
        val eventBus = EventBus()
        val untuned =
            PodNetworkTuning(
                RecordingManifests(),
                { ProbeResult.Admitted(emptyMap()) },
                eventBus,
                mapOf("net.core.somaxconn" to "65535"),
                Duration.ofMillis(50),
                Duration.ofMillis(10),
            )
        val steps = UpPostSteps(untuned, storage, observability, annotator, eventBus)

        assertThatThrownBy { steps.run(control, localTelemetry = true) }.isInstanceOf(CommandFailedException::class.java)
        verifyNoInteractions(storage, observability, annotator)
    }

    @Test
    fun `the pod network policy is in effect before the StorageClasses and the observability stack`() {
        val eventBus = EventBus()
        val sysctls = mapOf("net.core.somaxconn" to "65535")
        val manifests = RecordingManifests()
        val calls = mutableListOf<String>()
        val tuning =
            PodNetworkTuning(
                manifests,
                {
                    calls += "probe after ${manifests.applied.size} applied"
                    ProbeResult.Admitted(sysctls)
                },
                eventBus,
                sysctls,
                Duration.ofMillis(50),
                Duration.ofMillis(10),
            )
        whenever(storage.ensureLocalStorageClass()).thenAnswer {
            calls += "storage"
            Result.success(Unit)
        }
        whenever(storage.ensureLocalStorageWfcClass()).thenReturn(Result.success(Unit))
        whenever(observability.deploy(any())).thenAnswer {
            calls += "observability"
            Result.success(Unit)
        }

        UpPostSteps(tuning, storage, observability, annotator, eventBus).run(control, localTelemetry = false)

        assertThat(calls).containsExactly("probe after 2 applied", "storage", "observability")
    }
}
