package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventContext
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs one step of one node's pipeline under its [StepPolicy]: each attempt on its own virtual
 * thread, bounded by the policy's timeout, retried through resilience4j, and stopped at once when
 * the node's instance ends. It emits the step's start, completion and failure events.
 *
 * The attempt runs on a thread of its own so a timeout or an ended instance can abandon it; the
 * attempt thread is interrupted and carries the caller's command name, so its events keep it.
 * An interrupt of the caller (the control node failed, or `up` is stopping) is never retried: it
 * interrupts the attempt and is rethrown.
 *
 * @param retryInterval the pause between attempts; tests pass zero
 */
class StepRunner(
    private val eventBus: EventBus,
    private val health: HealthLatch,
    private val retryInterval: Duration = Duration.ofSeconds(DEFAULT_RETRY_INTERVAL_SECONDS),
) {
    /**
     * Runs [action] for [alias]'s [step] until it succeeds, its attempts run out, or [instanceEnded]
     * completes with the state EC2 reported. [action] receives the attempt number, from 1.
     */
    fun <T> run(
        alias: String,
        step: PipelineStep,
        policy: StepPolicy,
        instanceEnded: CompletableFuture<String>,
        action: (attempt: Int) -> T,
    ): StepOutcome<T> {
        val attempts = AtomicInteger()
        val retry = Retry.of("$alias-${step.wireName}", retryConfig<T>(policy.attempts))
        val attempt = Attempt(alias, step, policy, instanceEnded, action)
        return try {
            StepOutcome.Succeeded(Retry.decorateCheckedSupplier(retry) { attempt.run(attempts.incrementAndGet()) }.get())
        } catch (e: AttemptFailed) {
            StepOutcome.Failed(e.reason, policy.replaceable)
        } catch (e: InstanceEnded) {
            StepOutcome.InstanceEnded(e.state)
        }
    }

    /**
     * Up to [maxAttempts] attempts, [retryInterval] apart. Only an attempt's own failure is
     * retried: an ended instance, an interrupt or any other exception fails the step at once. An
     * attempt may throw a checked exception (SSH), hence `decorateCheckedSupplier` in [run].
     */
    private fun <T> retryConfig(maxAttempts: Int): RetryConfig =
        RetryConfig
            .custom<T>()
            .maxAttempts(maxAttempts)
            .intervalFunction { _ -> retryInterval.toMillis() }
            .retryOnException { it is AttemptFailed }
            .build()

    /** One step's attempts, sharing what every attempt needs. */
    private inner class Attempt<T>(
        private val alias: String,
        private val step: PipelineStep,
        private val policy: StepPolicy,
        private val instanceEnded: CompletableFuture<String>,
        private val action: (Int) -> T,
    ) {
        /** Runs attempt [number], and throws what the retry acts on when it did not succeed. */
        fun run(number: Int): T =
            when (val result = forgivingRun(number)) {
                is AttemptResult.Done -> result.value
                is AttemptResult.Ended -> throw InstanceEnded(result.state)
                is AttemptResult.Error -> throw AttemptFailed(result.reason)
            }

        /** Runs attempt [number]; a failure while the control plane was unhealthy is forgiven and run again. */
        private tailrec fun forgivingRun(number: Int): AttemptResult<T> {
            val ended = instanceEnded.getNow(null)
            if (ended != null) return AttemptResult.Ended(ended)
            eventBus.emit(Event.Provision.NodeStepStarted(alias, step.wireName, number))
            val began = System.nanoTime()
            val periodsBefore = health.unhealthyPeriods
            val result = once(number)
            val forgiven =
                result is AttemptResult.Error &&
                    policy.forgiveWhileUnhealthy &&
                    (health.unhealthyPeriods != periodsBefore || !health.healthy)
            if (!forgiven) {
                report(number, result, began)
                return result
            }
            health.awaitHealthy()
            return forgivingRun(number)
        }

        private fun report(
            number: Int,
            result: AttemptResult<T>,
            began: Long,
        ) {
            when (result) {
                is AttemptResult.Done -> {
                    val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
                    eventBus.emit(Event.Provision.NodeStepCompleted(alias, step.wireName, number, durationMs))
                }
                is AttemptResult.Error -> {
                    val willRetry = number < policy.attempts
                    eventBus.emit(
                        Event.Provision.NodeStepFailed(alias, step.wireName, number, policy.attempts, result.reason, willRetry),
                    )
                }
                is AttemptResult.Ended -> Unit
            }
        }

        private fun once(number: Int): AttemptResult<T> {
            val commandName = EventContext.current()
            val outcome = CompletableFuture<T>()
            val worker =
                Thread.ofVirtual().name("$alias-${step.wireName}-$number").start {
                    commandName?.let(EventContext::push)
                    runCatching { action(number) }.fold(outcome::complete, outcome::completeExceptionally)
                }
            return try {
                CompletableFuture.anyOf(outcome, instanceEnded).get(policy.timeout.toMillis(), TimeUnit.MILLISECONDS)
                finished(outcome, worker)
            } catch (_: TimeoutException) {
                worker.interrupt()
                AttemptResult.Error("timed out after ${describe(policy.timeout)}")
            } catch (_: ExecutionException) {
                finished(outcome, worker)
            } catch (e: InterruptedException) {
                worker.interrupt()
                throw e
            }
        }

        private fun finished(
            outcome: CompletableFuture<T>,
            worker: Thread,
        ): AttemptResult<T> {
            val ended = instanceEnded.getNow(null)
            return when {
                !outcome.isDone && ended != null -> {
                    worker.interrupt()
                    AttemptResult.Ended(ended)
                }
                outcome.isCompletedExceptionally ->
                    outcome.exceptionNow().let { AttemptResult.Error(it.message ?: it::class.java.simpleName) }
                else -> AttemptResult.Done(outcome.resultNow())
            }
        }
    }

    /** One attempt's result, before the policy decides what it means. */
    private sealed interface AttemptResult<out T> {
        data class Done<T>(
            val value: T,
        ) : AttemptResult<T>

        data class Error(
            val reason: String,
        ) : AttemptResult<Nothing>

        data class Ended(
            val state: String,
        ) : AttemptResult<Nothing>
    }

    /** An attempt failed; resilience4j retries it while attempts remain. */
    private class AttemptFailed(
        val reason: String,
    ) : RuntimeException(reason)

    /** The instance ended; never retried. */
    private class InstanceEnded(
        val state: String,
    ) : RuntimeException(state)

    private companion object {
        const val DEFAULT_RETRY_INTERVAL_SECONDS = 10L
        const val MILLIS_PER_SECOND = 1000L

        fun describe(timeout: Duration): String =
            if (timeout.toMillis() < MILLIS_PER_SECOND) "${timeout.toMillis()}ms" else "${timeout.toSeconds()}s"
    }
}
