package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.StressJobOutcome
import com.rustyrazorblade.easydblab.proxy.ObservabilityRoute
import io.github.oshai.kotlinlogging.KotlinLogging

/** One stress job as a cycle's list sees it. */
sealed interface ListedStressJob {
    /** The Kubernetes job name. */
    val name: String

    /** The alias of the node the stress pod runs (or ran) on; empty while unscheduled. */
    val host: String

    /** The job has not finished. */
    data class Running(
        override val name: String,
        override val host: String,
    ) : ListedStressJob

    /** The job finished with [outcome], [durationSeconds] after it started. */
    data class Finished(
        override val name: String,
        override val host: String,
        val outcome: StressJobOutcome,
        val durationSeconds: Long,
    ) : ListedStressJob
}

/**
 * Lists the cluster's stress jobs over one cycle's route.  It never starts or replaces a tunnel.
 *
 * @throws Exception when the jobs cannot be listed; the watcher logs it and skips the cycle.
 */
fun interface StressJobLister {
    /** The stress jobs of the cluster in [state], reached over [route]. */
    fun list(
        state: ClusterState,
        route: ObservabilityRoute,
    ): List<ListedStressJob>
}

/**
 * Turns each cycle's stress job list into `Stress.JobCompleted` events: one for each job it saw
 * running in an earlier cycle that has now finished.
 *
 * It remembers the jobs it saw running.  A job already finished when the stream started was never
 * seen running, so it is never reported.  A remembered job missing from a list was deleted (for
 * example by `cassandra stress stop`) and is forgotten without a report.  A list that fails is
 * logged and skipped with the memory kept, so a job that finished meanwhile is reported at the
 * next list that succeeds.  One stream owns one watcher and calls it from the command thread only.
 */
class StressJobWatcher(
    private val lister: StressJobLister,
) {
    private val running = mutableMapOf<String, String>()

    /** The completions found by listing the jobs of [state] over [route] now. */
    fun completed(
        state: ClusterState,
        route: ObservabilityRoute,
    ): List<Event.Stress.JobCompleted> {
        val jobs =
            runCatching { lister.list(state, route) }.getOrElse { e ->
                log.warn(e) { "Could not list the stress jobs; checking again next cycle" }
                return emptyList()
            }
        val completions =
            jobs.filterIsInstance<ListedStressJob.Finished>().mapNotNull { job ->
                running.remove(job.name)?.let { seenOn ->
                    Event.Stress.JobCompleted(job.name, job.host.ifEmpty { seenOn }, job.outcome, job.durationSeconds)
                }
            }
        val listed = jobs.map { it.name }.toSet()
        running.keys.retainAll(listed)
        jobs.filterIsInstance<ListedStressJob.Running>().forEach { job ->
            running[job.name] = job.host.ifEmpty { running[job.name].orEmpty() }
        }
        return completions
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
