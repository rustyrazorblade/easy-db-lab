package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import io.fabric8.kubernetes.api.model.admissionregistration.v1beta1.MutatingAdmissionPolicy
import io.fabric8.kubernetes.api.model.admissionregistration.v1beta1.MutatingAdmissionPolicyBinding
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tests for [PodNetworkTuning]: the policy is applied, the wait ends only when a dry-run pod carries
 * every injected value, and each way it fails names what went wrong.
 */
class PodNetworkTuningTest {
    private val sysctls = mapOf("net.core.somaxconn" to "65535", "net.ipv4.tcp_keepalive_time" to "60")
    private val manifests = RecordingManifests()
    private val events = mutableListOf<Event>()
    private val eventBus =
        EventBus().apply {
            addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        events += envelope.event
                    }

                    override fun close() = Unit
                },
            )
        }

    private fun tuning(probe: PodSysctlProbe) =
        PodNetworkTuning(manifests, probe, eventBus, sysctls, Duration.ofSeconds(1), Duration.ofMillis(10))

    @Test
    fun `applies the policy and its binding and reports the injected values`() {
        tuning { ProbeResult.Admitted(sysctls + ("net.ipv4.ping_group_range" to "0 0")) }.apply()

        assertThat(manifests.applied)
            .hasExactlyElementsOfTypes(MutatingAdmissionPolicy::class.java, MutatingAdmissionPolicyBinding::class.java)
        assertThat(events).containsExactly(Event.Platform.PodSysctlPolicyReady(Constants.NodeTuning.POD_SYSCTL_POLICY_NAME, sysctls))
    }

    @Test
    fun `waits until the API server has loaded the policy`() {
        var probes = 0
        tuning {
            probes++
            if (probes < 3) ProbeResult.Admitted(emptyMap()) else ProbeResult.Admitted(sysctls)
        }.apply()

        assertThat(probes).isEqualTo(3)
        assertThat(events.single()).isInstanceOf(Event.Platform.PodSysctlPolicyReady::class.java)
    }

    @Test
    fun `fails naming the keys a pod still lacks or carries with another value`() {
        val probe = PodSysctlProbe { ProbeResult.Admitted(mapOf("net.core.somaxconn" to "4096")) }

        assertThatThrownBy { tuning(probe).apply() }
            .isInstanceOf(CommandFailedException::class.java)
            .hasMessageContaining("net.core.somaxconn")
            .hasMessageContaining("net.ipv4.tcp_keepalive_time")
        assertThat(events).containsExactly(
            Event.Platform.PodSysctlPolicyNotInEffect(listOf("net.core.somaxconn", "net.ipv4.tcp_keepalive_time"), 1, null),
        )
    }

    @Test
    fun `a refused probe pod fails with the API server's reason`() {
        val probe = PodSysctlProbe { ProbeResult.Refused("policy expression failed") }

        assertThatThrownBy { tuning(probe).apply() }.isInstanceOf(CommandFailedException::class.java)
        assertThat(events).containsExactly(
            Event.Platform.PodSysctlPolicyNotInEffect(sysctls.keys.toList(), 1, "policy expression failed"),
        )
    }

    @Test
    fun `an unreachable API server is probed again until it answers`() {
        var probes = 0
        tuning {
            probes++
            if (probes == 1) ProbeResult.Unreachable("connect timed out") else ProbeResult.Admitted(sysctls)
        }.apply()

        assertThat(probes).isEqualTo(2)
        assertThat(events.single()).isInstanceOf(Event.Platform.PodSysctlPolicyReady::class.java)
    }

    @Test
    fun `a refusal is probed again, so a transient one after K3s starts does not fail up`() {
        var probes = 0
        tuning {
            probes++
            if (probes == 1) ProbeResult.Refused("serviceaccount \"default\" not found") else ProbeResult.Admitted(sysctls)
        }.apply()

        assertThat(probes).isEqualTo(2)
        assertThat(events).containsExactly(Event.Platform.PodSysctlPolicyReady(Constants.NodeTuning.POD_SYSCTL_POLICY_NAME, sysctls))
    }

    @Test
    fun `an API server unreachable until the deadline fails naming every key and the reason`() {
        val probe = PodSysctlProbe { ProbeResult.Unreachable("connect timed out") }

        assertThatThrownBy { tuning(probe).apply() }.isInstanceOf(CommandFailedException::class.java)
        assertThat(events).containsExactly(
            Event.Platform.PodSysctlPolicyNotInEffect(sysctls.keys.toList(), 1, "connect timed out"),
        )
    }

    @Test
    fun `a policy the API server rejects fails before any probe`() {
        manifests.failure = IllegalStateException("no kind MutatingAdmissionPolicy")
        var probes = 0

        assertThatThrownBy { tuning { ProbeResult.Admitted(sysctls).also { probes++ } }.apply() }
            .isInstanceOf(CommandFailedException::class.java)
            .hasMessageContaining("no kind MutatingAdmissionPolicy")
        assertThat(probes).isZero()
        assertThat(events).containsExactly(
            Event.Platform.PodSysctlPolicyNotApplied(Constants.NodeTuning.POD_SYSCTL_POLICY_NAME, "no kind MutatingAdmissionPolicy"),
        )
    }
}
