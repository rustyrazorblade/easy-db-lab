package com.rustyrazorblade.easydblab.services.bringup

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The control plane's current health, shared by every pipeline: `ControlPlaneHealthMonitor` sets
 * it, and the join limiter, the replacer and the join and Ready steps wait on it. [unhealthyPeriods]
 * counts each change to unhealthy, so a step can tell that the control plane was unhealthy at some
 * point during an attempt even when it has recovered by the time the attempt fails.
 *
 * It uses a [ReentrantLock], not `synchronized`, because its waiters are virtual threads.
 */
class HealthLatch {
    private val lock = ReentrantLock()
    private val becameHealthy = lock.newCondition()
    private var isHealthy = true
    private var periods = 0L

    /** Whether the control plane is healthy now. */
    val healthy: Boolean
        get() = lock.withLock { isHealthy }

    /** How many times the control plane has turned unhealthy. */
    val unhealthyPeriods: Long
        get() = lock.withLock { periods }

    /** The control plane turned unhealthy. A repeat while already unhealthy changes nothing. */
    fun markUnhealthy() =
        lock.withLock {
            if (isHealthy) {
                isHealthy = false
                periods++
            }
        }

    /** The control plane is healthy; every waiter is released. */
    fun markHealthy() =
        lock.withLock {
            isHealthy = true
            becameHealthy.signalAll()
        }

    /** Blocks until the control plane is healthy. */
    fun awaitHealthy() =
        lock.withLock {
            while (!isHealthy) becameHealthy.await()
        }
}
