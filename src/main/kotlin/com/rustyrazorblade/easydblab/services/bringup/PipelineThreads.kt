package com.rustyrazorblade.easydblab.services.bringup

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadFactory

/**
 * The virtual threads the bring-up runs its pipelines on, each one remembered, so [close] can
 * wait for every one of them to end.
 *
 * The executor's own `close` is not enough. A pipeline cancelled while it runs counts as done to
 * the executor at once, and its thread may still be finishing a step, a state save among them.
 * `up` would then go on, or a test would delete its directory, while that thread still writes.
 * [close] joins every thread, so nothing a pipeline does outlives the bring-up. A cancelled
 * pipeline is interrupted, and each step stops its attempt within its grace, so the wait is
 * bounded.
 */
class PipelineThreads :
    ThreadFactory,
    AutoCloseable {
    private val factory = Thread.ofVirtual().name("bring-up-", 0).factory()
    private val threads = ConcurrentLinkedQueue<Thread>()

    override fun newThread(task: Runnable): Thread = factory.newThread(task).also(threads::add)

    /** Waits for every thread made here to end. An interrupt does not cut the wait short; the flag is kept. */
    override fun close() {
        var interrupted = false
        threads.forEach { thread ->
            while (thread.isAlive) {
                try {
                    thread.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
