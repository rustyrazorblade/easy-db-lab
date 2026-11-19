package com.rustyrazorblade.easydblab.ssh

import com.rustyrazorblade.easydblab.Constants
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
        val runner = AtomicReference<Thread>()
        val result = CompletableFuture<T>()
        executor.execute {
            runner.set(Thread.currentThread())
            try {
                runCatching(block).fold(result::complete, result::completeExceptionally)
            } finally {
                runner.set(null)
                // A pooled thread must not carry this call's interrupt into the next one.
                Thread.interrupted()
            }
        }
        return awaitPassingInterrupts(result, runner)
    }

    private fun <T> awaitPassingInterrupts(
        result: CompletableFuture<T>,
        runner: AtomicReference<Thread>,
    ): T {
        var interrupted = false
        try {
            while (true) {
                try {
                    return result.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                    runner.get()?.interrupt()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
