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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
            Event.Platform.PodSysctlPolicyNotInEffect(
                Constants.NodeTuning.POD_SYSCTL_POLICY_NAME,
                listOf("net.core.somaxconn", "net.ipv4.tcp_keepalive_time"),
                1,
                null,
            ),
        )
    }

    @Test
    fun `a refused probe pod fails with the API server's reason`() {
        val probe = PodSysctlProbe { ProbeResult.Refused("policy expression failed") }

        assertThatThrownBy { tuning(probe).apply() }.isInstanceOf(CommandFailedException::class.java)
        assertThat(events).containsExactly(
            Event.Platform.PodSysctlPolicyNotInEffect(
                Constants.NodeTuning.POD_SYSCTL_POLICY_NAME,
                sysctls.keys.toList(),
                1,
                "policy expression failed",
            ),
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
            Event.Platform.PodSysctlPolicyNotInEffect(
                Constants.NodeTuning.POD_SYSCTL_POLICY_NAME,
                sysctls.keys.toList(),
                1,
                "connect timed out",
            ),
        )
    }

    @Test
    fun `a probe that throws fails up at once as a probe failure, not a timeout`() {
        val defect = IllegalArgumentException("unexpected response body")
        val probe = PodSysctlProbe { throw defect }

        assertThatThrownBy { tuning(probe).apply() }
            .isInstanceOf(CommandFailedException::class.java)
            .hasCause(defect)
            .hasMessageContaining("unexpected response body")
            .hasMessageNotContaining("after 1s")
        assertThat(events).containsExactly(
            Event.Platform.PodSysctlProbeFailed(Constants.NodeTuning.POD_SYSCTL_POLICY_NAME, "unexpected response body"),
        )
    }

    @Test
    fun `a probe that never answers is cut off at the deadline, so up waits no longer than it reports`() {
        val neverAnswers = CountDownLatch(1)
        val probe = PodSysctlProbe { neverAnswers.await().let { ProbeResult.Admitted(sysctls) } }
        val started = System.nanoTime()

        assertThatThrownBy { tuning(probe).apply() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3))
        val event = events.single() as Event.Platform.PodSysctlPolicyNotInEffect
        assertThat(event.missingKeys).containsExactlyElementsOf(sysctls.keys)
        assertThat(event.reason).contains("no answer before the 1s deadline")
    }

    @Test
    fun `an interrupt during the wait stops up without a policy failure`() {
        val probe = PodSysctlProbe { throw InterruptedException("stopped") }

        try {
            assertThatThrownBy { tuning(probe).apply() }.isInstanceOf(InterruptedException::class.java)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(events).isEmpty()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `interrupting up while a probe waits stops up with no event and interrupts the probe`() {
        val probeStarted = CountDownLatch(1)
        val probeInterrupted = CountDownLatch(1)
        val probe =
            PodSysctlProbe {
                probeStarted.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (e: InterruptedException) {
                    probeInterrupted.countDown()
                    throw e
                }
                ProbeResult.Admitted(sysctls)
            }
        val thrown = AtomicReference<Throwable>()
        val up = Thread { thrown.set(runCatching { tuning(probe).apply() }.exceptionOrNull()) }

        up.start()
        assertThat(probeStarted.await(5, TimeUnit.SECONDS)).isTrue()
        up.interrupt()
        up.join(Duration.ofSeconds(5))

        assertThat(up.isAlive).isFalse()
        assertThat(thrown.get()).isInstanceOf(InterruptedException::class.java)
        assertThat(probeInterrupted.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(events).isEmpty()
    }

    @Test
    fun `interrupting up while it waits between probes stops up with no event`() {
        val probed = CountDownLatch(1)
        val probe = PodSysctlProbe { ProbeResult.Admitted(emptyMap()).also { probed.countDown() } }
        val slowPolls = PodNetworkTuning(manifests, probe, eventBus, sysctls, Duration.ofSeconds(30), Duration.ofSeconds(10))
        val thrown = AtomicReference<Throwable>()
        val up = Thread { thrown.set(runCatching { slowPolls.apply() }.exceptionOrNull()) }

        up.start()
        assertThat(probed.await(5, TimeUnit.SECONDS)).isTrue()
        awaitSleeping(up)
        up.interrupt()
        up.join(Duration.ofSeconds(5))

        assertThat(up.isAlive).isFalse()
        assertThat(thrown.get()).isInstanceOf(InterruptedException::class.java)
        assertThat(events).isEmpty()
    }

    @Test
    fun `a poll interval longer than the time left does not stretch the wait past the deadline`() {
        val longPolls =
            PodNetworkTuning(
                manifests,
                { ProbeResult.Admitted(emptyMap()) },
                eventBus,
                sysctls,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
            )
        val started = System.nanoTime()

        assertThatThrownBy { longPolls.apply() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3))
        assertThat(events.single()).isInstanceOf(Event.Platform.PodSysctlPolicyNotInEffect::class.java)
    }

    /** Waits until [thread] is in the retry wait between probes, not waiting on a probe's answer. */
    private fun awaitSleeping(thread: Thread) {
        val until = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (thread.stackTrace.none { it.className == "java.lang.Thread" && it.methodName == "sleep" }) {
            check(System.nanoTime() < until) { "the poll never reached its wait between probes" }
            Thread.onSpinWait()
        }
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
