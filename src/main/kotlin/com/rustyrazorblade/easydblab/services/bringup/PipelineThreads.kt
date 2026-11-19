package com.rustyrazorblade.easydblab.services.bringup

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadFactory

/**
 * The virtual threads the bring-up runs its pipelines on, each one remembered, so [close] can
 * wait for every one of them to end.
 *
 * The executor's own `close` is not enough. A pipeline cancelled while it runs counts as done to
 * the executor at once, and its thread may still be finishing a step, a state save among them.
 * `up` would then go on, or a test would delete its directory, while that thread still writes.
 * [close] joins every thread, so nothing a pipeline does outlives the bring-up.
 *
 * A cancelled pipeline is interrupted, and each step stops its attempt within its own grace, but
 * work outside a step (a replacement's fleet call, an old Node's removal) has no such bound. So
 * the wait is bounded by [grace] too: a thread still alive after it is handed to [onStillRunning]
 * by name, and left behind, rather than hanging `up` with no word.
 *
 * @param grace how long [close] waits, in all, for every thread to end
 * @param onStillRunning called once with the names of the threads still running after [grace]
 */
class PipelineThreads(
    private val grace: Duration,
    private val onStillRunning: (List<String>) -> Unit,
) : ThreadFactory,
    AutoCloseable {
    private val factory = Thread.ofVirtual().name("bring-up-", 0).factory()
    private val threads = ConcurrentLinkedQueue<Thread>()

    override fun newThread(task: Runnable): Thread = factory.newThread(task).also(threads::add)

    /**
     * Waits up to [grace] for every thread made here to end, then reports any still running. An
     * interrupt does not cut the wait short; the flag is kept.
     */
    override fun close() {
        val deadline = System.nanoTime() + grace.toNanos()
        var interrupted = false
        threads.forEach { thread ->
            var left = deadline - System.nanoTime()
            while (thread.isAlive && left > 0) {
                try {
                    thread.join(Duration.ofNanos(left))
                } catch (_: InterruptedException) {
                    interrupted = true
                }
                left = deadline - System.nanoTime()
            }
        }
        val running = threads.filter { it.isAlive }.map { it.name }
        if (running.isNotEmpty()) onStillRunning(running)
        if (interrupted) Thread.currentThread().interrupt()
    }
}
