package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.providers.aws.pollUntil
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import java.time.Duration
import java.time.Instant

/**
 * Waits for the pod state a test's setup or teardown needs by reading it directly with GETs, never
 * through a watch.
 *
 * fabric8's `waitUntilCondition`, `waitUntilReady` and `informOnCondition` wait on a watch, and in
 * CI a watch sometimes never delivered an event that a direct GET found (#1232). A setup wait that
 * depends on a watch can then fail a test for a reason that is not the code under test's. A wait on
 * the behaviour a test proves goes through the code under test instead.
 */
object DirectReadWait {
    private val POLL: Duration = Duration.ofMillis(250)

    /**
     * Reads with [read] until [done] accepts the result, for at most [timeout], and returns that
     * result. A read that throws is tried again within the time. When the time runs out, it fails
     * with [what] and [describe] of the last read, or, when the last read threw, with that exception
     * as the cause. An interrupt ends the wait by throwing.
     */
    fun <T> await(
        what: String,
        timeout: Duration,
        describe: (T) -> String,
        done: (T) -> Boolean,
        read: () -> T,
    ): T {
        val last = pollOrFail(what, timeout, done, read)
        if (!done(last)) {
            throw AssertionError("$what: not reached after ${timeout.toSeconds()}s; last read: ${describe(last)}")
        }
        return last
    }

    /**
     * Runs the poll for [await]. A last read that failed becomes an [AssertionError] that names
     * [what] and carries the failure as its cause, so the report says which wait it was.
     */
    private fun <T> pollOrFail(
        what: String,
        timeout: Duration,
        done: (T) -> Boolean,
        read: () -> T,
    ): T =
        try {
            pollUntil(
                operationName = what,
                maxAttempts = Int.MAX_VALUE,
                interval = POLL,
                deadline = Instant.now().plus(timeout),
                done = done,
                poll = read,
            )
        } catch (e: KubernetesClientException) {
            // fabric8 turns an interrupted read into a KubernetesClientException and sets the
            // interrupt flag again; that read ends the wait, as any interrupt does.
            if (Thread.currentThread().isInterrupted) throw e
            throw AssertionError("$what: not reached after ${timeout.toSeconds()}s; last read failed", e)
        }

    /**
     * Reads [pod] until [done] accepts it (null when there is no such pod), and returns the last
     * read. [what] names the state waited for, as in "is Running".
     */
    fun awaitPod(
        client: KubernetesClient,
        pod: PodRef,
        what: String,
        timeout: Duration,
        done: (Pod?) -> Boolean,
    ): Pod? =
        await("pod ${pod.name} in namespace ${pod.namespace} $what", timeout, ::describePod, done) {
            client
                .pods()
                .inNamespace(pod.namespace)
                .withName(pod.name)
                .get()
        }

    /**
     * Lists [pods] until [done] accepts the list, and returns the last list. [what] names the state
     * waited for, as in "are all gone".
     */
    fun awaitPods(
        client: KubernetesClient,
        pods: PodSelection,
        what: String,
        timeout: Duration,
        done: (List<Pod>) -> Boolean,
    ): List<Pod> =
        await(
            "pods ${pods.labelSelector} in namespace ${pods.namespace} $what",
            timeout,
            { found -> found.joinToString("; ", transform = ::describePod).ifEmpty { "no pods" } },
            done,
        ) {
            client
                .pods()
                .inNamespace(pods.namespace)
                .withLabelSelector(pods.labelSelector)
                .list()
                .items
        }

    /** The pod [name] in [namespace], for [awaitPod]. */
    data class PodRef(
        val namespace: String,
        val name: String,
    )

    /** The pods in [namespace] that match [labelSelector], for [awaitPods]. */
    data class PodSelection(
        val namespace: String,
        val labelSelector: String,
    )

    /** A pod's phase, deletion, and container states on one line, for a failed wait. */
    private fun describePod(pod: Pod?): String {
        if (pod == null) return "absent"
        val containers =
            pod.status?.containerStatuses.orEmpty().joinToString { cs ->
                val state =
                    cs.state?.waiting?.let { "waiting ${it.reason}" }
                        ?: cs.state?.terminated?.let { "terminated ${it.reason}" }
                        ?: cs.state?.running?.let { "running" }
                "${cs.name} ready=${cs.ready} restarts=${cs.restartCount} $state"
            }
        return "${pod.metadata.name} phase=${pod.status?.phase} deletionTimestamp=${pod.metadata.deletionTimestamp} [$containers]"
    }
}
