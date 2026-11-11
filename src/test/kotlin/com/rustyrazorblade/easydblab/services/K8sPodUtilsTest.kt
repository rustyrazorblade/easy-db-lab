package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.PodBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [K8sPodUtils.awaitsReadiness], which decides which pods a namespace readiness wait
 * holds out for. A Job pod that ran to completion is never Ready again, so waiting on it only ends
 * in a timeout.
 */
class K8sPodUtilsTest {
    private fun podInPhase(
        phase: String?,
        ownerKind: String? = null,
    ) = PodBuilder()
        .withNewMetadata()
        .withName("pod")
        .apply {
            if (ownerKind != null) {
                addNewOwnerReference().withKind(ownerKind).withName("owner").endOwnerReference()
            }
        }.endMetadata()
        .withNewStatus()
        .withPhase(phase)
        .endStatus()
        .build()

    @Test
    fun `a pod that ran to completion is not awaited`() {
        assertThat(K8sPodUtils.awaitsReadiness(podInPhase("Succeeded"))).isFalse()
    }

    @Test
    fun `running, pending and unreported pods are awaited`() {
        for (phase in listOf("Running", "Pending", "Unknown", null)) {
            assertThat(K8sPodUtils.awaitsReadiness(podInPhase(phase)))
                .describedAs("phase=$phase")
                .isTrue()
        }
    }

    @Test
    fun `a failed Job pod is terminal and not awaited`() {
        assertThat(K8sPodUtils.awaitsReadiness(podInPhase("Failed", ownerKind = "Job"))).isFalse()
    }

    @Test
    fun `a failed pod not owned by a Job is still awaited`() {
        assertThat(K8sPodUtils.awaitsReadiness(podInPhase("Failed"))).isTrue()
        assertThat(K8sPodUtils.awaitsReadiness(podInPhase("Failed", ownerKind = "ReplicaSet"))).isTrue()
    }
}
