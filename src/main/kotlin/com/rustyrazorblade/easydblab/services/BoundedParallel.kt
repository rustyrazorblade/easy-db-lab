package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Runs one action per item on a fixed pool of at most [maxConcurrency] threads, and returns each
 * item's outcome in input order.
 *
 * Every per-node fan-out in `up` (SSH setup, K3s agents, registry TLS, the Cilium image check,
 * the SSH readiness probe) goes through here, so a 1,000-node cluster opens at most
 * [maxConcurrency] connections at once instead of one thread per node. Every item runs whatever
 * happens to the others; a failure is captured in that item's [Result].
 */
class BoundedParallel(
    private val maxConcurrency: Int = Constants.Scale.HOST_CONCURRENCY,
) {
    init {
        require(maxConcurrency >= 1) { "maxConcurrency must be at least 1, got $maxConcurrency" }
    }

    /** Runs [action] on every item in [items], at most [maxConcurrency] at once. */
    fun <I, T> map(
        items: List<I>,
        action: (I) -> T,
    ): List<Result<T>> {
        if (items.size <= 1) return items.map { runCatching { action(it) } }
        val threads = minOf(maxConcurrency, items.size)
        return Executors.newFixedThreadPool(threads).use { pool ->
            pool
                .invokeAll(items.map { item -> Callable { action(item) } })
                .map { future ->
                    try {
                        Result.success(future.get())
                    } catch (e: ExecutionException) {
                        Result.failure(e.cause ?: e)
                    }
                }
        }
    }
}
