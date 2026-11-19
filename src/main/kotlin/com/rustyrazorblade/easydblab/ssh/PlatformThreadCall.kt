package com.rustyrazorblade.easydblab.ssh

import com.rustyrazorblade.easydblab.Constants
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Runs a blocking SSH library call on a platform thread when its caller is a virtual thread.
 *
 * Apache MINA blocks inside `synchronized` blocks and in `Object.wait` (channel open, `waitFor`),
 * and on JDK 21 a virtual thread blocked there pins its carrier.  Bring-up calls SSH from one
 * virtual thread per node, so enough concurrent commands pin every carrier; a virtual thread that
 * must run to release a lock they wait on (a log appender's, handed to an unmounted waiter) then
 * never mounts, and every command hangs.  Here the virtual caller only waits on a future, which
 * unmounts it, and the pinning-prone code runs on a platform thread of its own.
 *
 * A platform caller runs the call directly.  An interrupt of the caller is passed on to the
 * platform thread, and the caller then waits for the call to end, as it would have waited for the
 * call itself; the interrupt is restored once the call returns or throws.  The call's own exception,
 * checked or not, is rethrown unwrapped.
 */
object PlatformThreadCall {
    private val threads = AtomicInteger()
    private val executor: ExecutorService =
        Executors.newCachedThreadPool { task ->
            Thread
                .ofPlatform()
                .daemon()
                .name("${Constants.Ssh.CALL_THREAD_NAME}-${threads.incrementAndGet()}")
                .unstarted(task)
        }

    /** Runs [block], on a platform thread when the current thread is virtual. */
    fun <T> run(block: () -> T): T {
        if (!Thread.currentThread().isVirtual) return block()
        val handOff = InterruptHandOff()
        val result = CompletableFuture<T>()
        executor.execute {
            handOff.start()
            try {
                runCatching(block).fold(result::complete, result::completeExceptionally)
            } finally {
                handOff.finish()
            }
        }
        return awaitPassingInterrupts(result, handOff)
    }

    private fun <T> awaitPassingInterrupts(
        result: CompletableFuture<T>,
        handOff: InterruptHandOff,
    ): T {
        // A caller interrupted before the call is asked to stop it as soon as it starts.
        var interrupted = Thread.interrupted()
        if (interrupted) handOff.interrupt()
        try {
            while (true) {
                try {
                    return result.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                    handOff.interrupt()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /**
     * Passes a caller's interrupt to the pooled thread running its call, and only while it runs that
     * call. One lock orders the caller's interrupt against the call's start and end: an interrupt
     * before the start is applied when it starts, and none lands after the end, where it would reach
     * the next, unrelated call on the same thread.
     */
    private class InterruptHandOff {
        private val lock = ReentrantLock()
        private var runner: Thread? = null
        private var pending = false

        /** On the pooled thread, before the call: it starts clear, or interrupted if its caller was. */
        fun start() =
            lock.withLock {
                Thread.interrupted()
                runner = Thread.currentThread()
                if (pending) runner?.interrupt()
            }

        /** On the pooled thread, after the call: no later interrupt reaches it, and it ends clear. */
        fun finish() =
            lock.withLock {
                runner = null
                Thread.interrupted()
            }

        /** On the caller: interrupts the call if it runs, or marks it to be interrupted when it starts. */
        fun interrupt() =
            lock.withLock {
                pending = true
                runner?.interrupt()
            }
    }
}
