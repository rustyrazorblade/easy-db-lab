package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.StressJobOutcome
import com.rustyrazorblade.easydblab.proxy.ObservabilityRoute
import io.github.oshai.kotlinlogging.Level
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StressJobWatcherTest : BaseKoinTest() {
    /** Answers each list with the next scripted result. */
    private class ScriptedLister(
        answers: List<Result<List<ListedStressJob>>>,
    ) : StressJobLister {
        private val remaining = ArrayDeque(answers)
        val routes = mutableListOf<ObservabilityRoute>()

        override fun list(
            state: com.rustyrazorblade.easydblab.configuration.ClusterState,
            route: ObservabilityRoute,
        ): List<ListedStressJob> {
            routes += route
            return remaining.removeFirst().getOrThrow()
        }
    }

    private val state = runningCluster()
    private val route = ObservabilityRoute.Socks(PORT)

    private fun running(name: String) = ListedStressJob.Running(name, "app0")

    private fun finished(
        name: String,
        outcome: StressJobOutcome,
        seconds: Long = 605,
    ) = ListedStressJob.Finished(name, "app0", outcome, seconds)

    private fun ok(vararg jobs: ListedStressJob) = Result.success(jobs.toList())

    private fun StressJobWatcher.cycles(n: Int) = (1..n).map { completed(state, route) }

    @Test
    fun `a job seen running that succeeds is reported once`() {
        val watcher =
            StressJobWatcher(
                ScriptedLister(
                    listOf(
                        ok(running("j1")),
                        ok(finished("j1", StressJobOutcome.SUCCEEDED)),
                        ok(finished("j1", StressJobOutcome.SUCCEEDED)),
                    ),
                ),
            )

        val reports = watcher.cycles(3)

        assertThat(reports[0]).isEmpty()
        assertThat(reports[1]).containsExactly(Event.Stress.JobCompleted("j1", "app0", StressJobOutcome.SUCCEEDED, 605))
        assertThat(reports[2]).isEmpty()
    }

    @Test
    fun `a job seen running that fails is reported as failed`() {
        val watcher = StressJobWatcher(ScriptedLister(listOf(ok(running("j1")), ok(finished("j1", StressJobOutcome.FAILED, 12)))))

        val reports = watcher.cycles(2)

        assertThat(reports[1]).containsExactly(Event.Stress.JobCompleted("j1", "app0", StressJobOutcome.FAILED, 12))
    }

    @Test
    fun `a job already finished when the stream started is never reported`() {
        val watcher =
            StressJobWatcher(
                ScriptedLister(listOf(ok(finished("old", StressJobOutcome.SUCCEEDED)), ok(finished("old", StressJobOutcome.SUCCEEDED)))),
            )

        assertThat(watcher.cycles(2)).allSatisfy { assertThat(it).isEmpty() }
    }

    @Test
    fun `a running job that is deleted is never reported, even if a job with its name finishes later`() {
        val watcher =
            StressJobWatcher(ScriptedLister(listOf(ok(running("j1")), ok(), ok(finished("j1", StressJobOutcome.SUCCEEDED)))))

        assertThat(watcher.cycles(3)).allSatisfy { assertThat(it).isEmpty() }
    }

    @Test
    fun `a failed list reports nothing, and a job that finished meanwhile is reported at the next good list`() {
        val watcher =
            StressJobWatcher(
                ScriptedLister(
                    listOf(
                        ok(running("j1")),
                        Result.failure(IllegalStateException("connection refused")),
                        ok(finished("j1", StressJobOutcome.SUCCEEDED)),
                    ),
                ),
            )

        val reports = watcher.cycles(3)

        assertThat(reports[1]).isEmpty()
        assertThat(reports[2]).containsExactly(Event.Stress.JobCompleted("j1", "app0", StressJobOutcome.SUCCEEDED, 605))
    }

    @Test
    fun `a host seen while running is kept when later lists name none`() {
        val watcher =
            StressJobWatcher(
                ScriptedLister(
                    listOf(
                        ok(ListedStressJob.Running("j1", "app2")),
                        ok(ListedStressJob.Running("j1", "")),
                        ok(ListedStressJob.Finished("j1", "", StressJobOutcome.SUCCEEDED, 5)),
                    ),
                ),
            )

        val reports = watcher.cycles(3)

        assertThat(reports[2]).containsExactly(Event.Stress.JobCompleted("j1", "app2", StressJobOutcome.SUCCEEDED, 5))
    }

    @Test
    fun `a run of list failures is logged at warn once, at debug after, and at info when it ends`() {
        val log = ListFailureLog()

        assertThat(log.failed(stopping = false)).isEqualTo(Level.WARN)
        assertThat(log.failed(stopping = false)).isEqualTo(Level.DEBUG)
        assertThat(log.failed(stopping = false)).isEqualTo(Level.DEBUG)
        assertThat(log.succeeded()).isEqualTo(Level.INFO)
        assertThat(log.succeeded()).isNull()
        assertThat(log.failed(stopping = false)).isEqualTo(Level.WARN)
    }

    @Test
    fun `a list failure while the stream is stopping is not logged at warn`() {
        val log = ListFailureLog()

        assertThat(log.failed(stopping = true)).isEqualTo(Level.DEBUG)
        assertThat(log.succeeded()).isNull()
    }

    @Test
    fun `each list goes over the cycle's route`() {
        val lister = ScriptedLister(listOf(ok(), ok()))
        val watcher = StressJobWatcher(lister)

        watcher.completed(state, ObservabilityRoute.Socks(PORT))
        watcher.completed(state, ObservabilityRoute.Socks(PORT + 1))

        assertThat(lister.routes).containsExactly(ObservabilityRoute.Socks(PORT), ObservabilityRoute.Socks(PORT + 1))
    }

    private companion object {
        const val PORT = 21000
    }
}
