package com.rustyrazorblade.easydblab.configuration

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.utils.Serialization
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CapabilityProbePodsTest {
    private val namespace = "lab"

    /** [pod] with the one field [capability] tests set back to the baseline's value. */
    private fun withoutTestedField(
        pod: Pod,
        capability: NodeCapability,
    ): Pod {
        val copy = Serialization.clone(pod)
        copy.metadata.name = CapabilityProbePods.baseline(namespace).metadata.name
        when (capability) {
            NodeCapability.PRIVILEGED ->
                copy.spec.containers[0].securityContext.apply {
                    privileged = null
                    allowPrivilegeEscalation = false
                }
            NodeCapability.HOST_NETWORK -> copy.spec.hostNetwork = null
            NodeCapability.HOST_PATH -> {
                copy.spec.volumes = emptyList()
                copy.spec.containers[0].volumeMounts = emptyList()
            }
            else -> error("not probed: $capability")
        }
        return copy
    }

    @Test
    fun `each probe Pod differs from the baseline only in the field it tests`() {
        val baseline = CapabilityProbePods.baseline(namespace)

        for ((capability, pod) in CapabilityProbePods.probes(namespace)) {
            assertThat(pod).isNotEqualTo(baseline)
            assertThat(withoutTestedField(pod, capability)).describedAs(capability.name).isEqualTo(baseline)
        }
    }

    @Test
    fun `the privileged probe does not forbid privilege escalation, which the API server rejects`() {
        val securityContext =
            CapabilityProbePods
                .probes(namespace)
                .getValue(NodeCapability.PRIVILEGED)
                .spec.containers
                .single()
                .securityContext

        assertThat(securityContext.privileged).isTrue()
        // Pod validation refuses privileged: true with allowPrivilegeEscalation: false (422), even
        // under dryRun=All, which would report PRIVILEGED absent on every cluster.
        assertThat(securityContext.allowPrivilegeEscalation).isNotEqualTo(false)
    }

    @Test
    fun `the probes cover exactly the three admission capabilities, each under its own name`() {
        val probes = CapabilityProbePods.probes(namespace)

        assertThat(probes.keys)
            .containsExactly(NodeCapability.PRIVILEGED, NodeCapability.HOST_NETWORK, NodeCapability.HOST_PATH)
        assertThat(probes.values.map { it.metadata.name } + CapabilityProbePods.baseline(namespace).metadata.name)
            .doesNotHaveDuplicates()
            .allSatisfy { assertThat(it).startsWith("edl-capability-probe") }
    }

    @Test
    fun `the baseline meets Pod Security restricted`() {
        val pod = CapabilityProbePods.baseline(namespace)
        val container = pod.spec.containers.single()

        assertThat(pod.metadata.namespace).isEqualTo(namespace)
        assertThat(pod.spec.securityContext.runAsNonRoot).isTrue()
        assertThat(pod.spec.securityContext.seccompProfile.type).isEqualTo("RuntimeDefault")
        assertThat(container.securityContext.allowPrivilegeEscalation).isFalse()
        assertThat(container.securityContext.capabilities.drop).containsExactly("ALL")
        assertThat(pod.spec.hostNetwork == true).isFalse()
        assertThat(pod.spec.volumes).isEmpty()
    }
}
