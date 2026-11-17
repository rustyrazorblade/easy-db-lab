package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventContext
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class StepRunnerTest {
    private val envelopes = CopyOnWriteArrayList<EventEnvelope>()
    private val eventBus =
        EventBus().also {
            it.addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        envelopes += envelope
                    }

                    override fun close() = Unit
                },
            )
        }
    private val health = HealthLatch()
    private val runner = StepRunner(eventBus, health, retryInterval = Duration.ZERO)
    private val neverEnds = CompletableFuture<String>()

    private fun policy(
        attempts: Int,
        timeout: Duration = Duration.ofSeconds(5),
        forgiveWhileUnhealthy: Boolean = false,
    ) = StepPolicy(attempts, timeout, replaceable = true, forgiveWhileUnhealthy = forgiveWhileUnhealthy)

    private fun events() = envelopes.map { it.event }

    @ParameterizedTest(name = "{0} attempts, first success on attempt {1}")
    @CsvSource("1, 1", "2, 1", "2, 2", "3, 3")
    fun `a step that succeeds within its attempts succeeds after exactly that many`(
        attempts: Int,
        succeedOn: Int,
    ) {
        val calls = AtomicInteger()

        val outcome =
            runner.run("db1", PipelineStep.REGISTRY_TLS, policy(attempts), neverEnds) { attempt ->
                calls.incrementAndGet()
                check(attempt >= succeedOn) { "not yet" }
                "ok"
            }

        assertThat(outcome).isEqualTo(StepOutcome.Succeeded("ok"))
        assertThat(calls.get()).isEqualTo(succeedOn)
        assertThat(events().filterIsInstance<Event.Provision.NodeStepFailed>().map { it.willRetry })
            .containsExactlyElementsOf(List(succeedOn - 1) { true })
    }

    @Test
    fun `a step that fails every attempt fails with the last reason and says it will not retry`() {
        val outcome =
            runner.run("db1", PipelineStep.SETUP, policy(2), neverEnds) { attempt -> error("exit $attempt") }

        assertThat(outcome).isEqualTo(StepOutcome.Failed("exit 2", replaceable = true))
        assertThat(events().filterIsInstance<Event.Provision.NodeStepFailed>()).containsExactly(
            Event.Provision.NodeStepFailed("db1", "setup", 1, 2, "exit 1", willRetry = true),
            Event.Provision.NodeStepFailed("db1", "setup", 2, 2, "exit 2", willRetry = false),
        )
    }

    @Test
    fun `an attempt that runs past its timeout fails as a timeout`() {
        val outcome =
            runner.run("db1", PipelineStep.SETUP, policy(1, timeout = Duration.ofMillis(50)), neverEnds) {
                Thread.sleep(10_000)
            }

        assertThat(outcome).isEqualTo(StepOutcome.Failed("timed out after 50ms", replaceable = true))
    }

    /**
     * A timed-out attempt is interrupted and waited for before the next one starts, so two
     * attempts of one step never run together on one instance.
     */
    @Test
    fun `a timed-out attempt has stopped before the retry starts`() {
        val running = AtomicInteger()
        val overlapped = AtomicInteger()

        val outcome =
            runner.run("db1", PipelineStep.SETUP, policy(2, timeout = Duration.ofMillis(50)), neverEnds) { attempt ->
                if (running.incrementAndGet() > 1) overlapped.incrementAndGet()
                try {
                    if (attempt == 1) sleepThenCleanUp()
                    "ok"
                } finally {
                    running.decrementAndGet()
                }
            }

        assertThat(outcome).isEqualTo(StepOutcome.Succeeded("ok"))
        assertThat(overlapped.get()).isZero()
    }

    @Test
    fun `an attempt that ignores its interrupt past the grace fails the step at once, with no retry`() {
        val stuckRunner = StepRunner(eventBus, health, retryInterval = Duration.ZERO, abandonGrace = Duration.ofMillis(50))
        val calls = AtomicInteger()
        val release = CountDownLatch(1)

        val outcome =
            stuckRunner.run("db1", PipelineStep.SETUP, policy(3, timeout = Duration.ofMillis(50)), neverEnds) {
                calls.incrementAndGet()
                // Swallows every interrupt, as a blocking call that ignores them would.
                while (!awaitIgnoringInterrupt(release)) continue
            }
        release.countDown()

        assertThat(outcome).isEqualTo(
            StepOutcome.Failed("timed out after 50ms, and the attempt did not stop within 50ms", replaceable = true),
        )
        assertThat(calls.get()).isEqualTo(1)
    }

    /** Sleeps until interrupted, then spends [CLEANUP_MS] cleaning up, as an attempt that stops slowly would. */
    private fun sleepThenCleanUp() {
        try {
            Thread.sleep(10_000)
        } catch (_: InterruptedException) {
            Thread.sleep(CLEANUP_MS)
        }
    }

    /** Whether [latch] opened; false when the wait was interrupted, which is swallowed. */
    private fun awaitIgnoringInterrupt(latch: CountDownLatch): Boolean =
        try {
            latch.await()
            true
        } catch (_: InterruptedException) {
            false
        }

    @Test
    fun `an instance that ends mid-step stops the step at once, with no retry`() {
        val ended = CompletableFuture<String>()
        val calls = AtomicInteger()
        val started = CountDownLatch(1)

        Thread.ofVirtual().start {
            started.await()
            ended.complete("terminated")
        }
        val outcome =
            runner.run("db2", PipelineStep.SETUP, policy(2), ended) {
                calls.incrementAndGet()
                started.countDown()
                Thread.sleep(10_000)
            }

        assertThat(outcome).isEqualTo(StepOutcome.InstanceEnded("terminated"))
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `a failure while the control plane is unhealthy is not counted, and the attempt runs again once it recovers`() {
        val calls = AtomicInteger()

        val outcome =
            runner.run("db3", PipelineStep.JOIN, policy(1, forgiveWhileUnhealthy = true), neverEnds) {
                if (calls.incrementAndGet() == 1) {
                    health.markUnhealthy()
                    Thread.ofVirtual().start {
                        Thread.sleep(50)
                        health.markHealthy()
                    }
                    error("registration timed out")
                }
                "registered"
            }

        assertThat(outcome).isEqualTo(StepOutcome.Succeeded("registered"))
        assertThat(calls.get()).isEqualTo(2)
        assertThat(events().filterIsInstance<Event.Provision.NodeStepFailed>()).isEmpty()
    }

    @Test
    fun `a step that does not forgive counts a failure while the control plane is unhealthy`() {
        val outcome =
            runner.run("db3", PipelineStep.SETUP, policy(1), neverEnds) {
                health.markUnhealthy()
                error("exit 1")
            }

        assertThat(outcome).isEqualTo(StepOutcome.Failed("exit 1", replaceable = true))
    }

    @Test
    fun `an interrupt stops the step and is never retried`() {
        val calls = AtomicInteger()
        val failure = CompletableFuture<Throwable>()
        val running = CountDownLatch(1)

        val pipeline =
            Thread.ofVirtual().start {
                try {
                    runner.run("db4", PipelineStep.SETUP, policy(3), neverEnds) {
                        calls.incrementAndGet()
                        running.countDown()
                        Thread.sleep(10_000)
                    }
                    failure.complete(null)
                } catch (e: InterruptedException) {
                    failure.complete(e)
                }
            }
        running.await()
        pipeline.interrupt()

        assertThat(failure.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedException::class.java)
        assertThat(calls.get()).isEqualTo(1)
    }

    /** resilience4j turns an interrupt of its pause into the last failure; the step must still stop as interrupted. */
    @Test
    fun `an interrupt in the pause between attempts stops the step as interrupted, not failed`() {
        val pausing = StepRunner(eventBus, health, retryInterval = Duration.ofSeconds(30))
        val failed = CountDownLatch(1)
        val result = CompletableFuture<Any?>()

        val pipeline =
            Thread.ofVirtual().start {
                try {
                    result.complete(
                        pausing.run("db5", PipelineStep.SETUP, policy(2), neverEnds) {
                            failed.countDown()
                            error("exit 1")
                        },
                    )
                } catch (e: InterruptedException) {
                    result.complete(e)
                }
            }
        failed.await()
        // The first attempt failed; the runner is in, or about to start, the 30 s pause.
        Thread.sleep(PAUSE_SETTLE_MS)
        pipeline.interrupt()

        assertThat(result.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedException::class.java)
    }

    @Test
    fun `step events carry the command name of the pipeline that ran them`() {
        EventContext.push("up")
        try {
            runner.run("db5", PipelineStep.SSH, policy(1), neverEnds) { eventBus.emit(Event.Provision.SkippingNodeSetup) }
        } finally {
            EventContext.pop()
        }

        assertThat(envelopes.map { it.commandName }).containsOnly("up")
        assertThat(events().map { it::class }).containsExactly(
            Event.Provision.NodeStepStarted::class,
            Event.Provision.SkippingNodeSetup::class,
            Event.Provision.NodeStepCompleted::class,
        )
    }

    @Test
    fun `a step with no attempts is refused`() {
        assertThatThrownBy { StepPolicy(0, Duration.ofSeconds(1), replaceable = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        /** Long enough for the failed attempt to reach the pause; an interrupt before it is caught the same way. */
        const val PAUSE_SETTLE_MS = 50L

        /** How long the timed-out attempt keeps working after its interrupt; well past its 50 ms timeout. */
        const val CLEANUP_MS = 200L
    }
}
