package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.UnreadyPod
import java.time.Duration
import java.time.Instant

/**
 * Turns what a rollout wait sees into the events it reports: the progress lines while it waits and
 * the timeout when it gives up.
 *
 * It exists so the wait's report is decided apart from the polling. A timeout names each workload
 * still pending and the pods it waits on, with their node and state, because the rollout wait runs
 * before the namespace readiness wait and is the one that times out when a pod never becomes Ready.
 */
object RolloutWaitReport {
    /** The timeout of a wait of [timeoutSeconds] whose last look was [look]. */
    fun timedOut(
        timeoutSeconds: Int,
        look: RolloutsLook.Pending,
    ): Event.K8s.RolloutsTimedOut =
        Event.K8s.RolloutsTimedOut(
            timeoutSeconds = timeoutSeconds,
            pending = look.workloads.map { it.toString() },
            reasons = look.reasons,
            unreadyPods =
                look.unready.map {
                    UnreadyPod(
                        workload = it.workload.toString(),
                        pod = it.pod.metadata?.name ?: "unknown",
                        node = it.pod.spec?.nodeName,
                        state = K8sPodUtils.describeState(it.pod),
                    )
                },
            unreadyOmitted = look.unreadyOmitted,
        )

    /**
     * The progress of one wait that started at [start] on [initial] workloads: a look yields a
     * progress event when the set of pending workloads changed since the last report, or when
     * [interval] passed without one. Not thread-safe; one wait polls it from one thread.
     */
    class Progress(
        private val start: Instant,
        initial: List<String>,
        private val interval: Duration,
    ) {
        private var reported = initial.toSet()
        private var reportedAt = start

        /** The progress event due at [now] with [pending] workloads left, or null when none is due. */
        fun next(
            now: Instant,
            pending: List<String>,
        ): Event.K8s.RolloutsProgress? {
            val current = pending.toSet()
            if (current == reported && Duration.between(reportedAt, now) < interval) return null
            reported = current
            reportedAt = now
            return Event.K8s.RolloutsProgress(Duration.between(start, now).seconds, pending)
        }
    }
}
