package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.Constants
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * How many replacements `up` may still make: at most [perNodeLimit] for one alias, and at most
 * [clusterLimit] in all. The cluster limit is the larger of `MIN_CLUSTER_REPLACEMENTS` and
 * `CLUSTER_REPLACEMENT_FRACTION` of the nodes, rounded up, so a fault every node has (a setup bug,
 * a bad package) fails `up` instead of replacing the whole cluster twice over.
 *
 * Thread-safe: every pipeline asks it from its own thread.
 *
 * @param nodeCount the database and application nodes `up` brings up
 */
class ReplacementBudget(
    nodeCount: Int,
    private val perNodeLimit: Int = Constants.Scale.MAX_NODE_REPLACEMENTS,
) {
    /** The most replacements `up` makes in all. */
    val clusterLimit: Int =
        maxOf(
            Constants.Scale.MIN_CLUSTER_REPLACEMENTS,
            BigDecimal(nodeCount)
                .multiply(BigDecimal.valueOf(Constants.Scale.CLUSTER_REPLACEMENT_FRACTION))
                .setScale(0, RoundingMode.CEILING)
                .toInt(),
        )

    private val lock = ReentrantLock()
    private val byAlias = mutableMapOf<String, Int>()

    /** The replacements granted so far. */
    val used: Int
        get() = lock.withLock { byAlias.values.sum() }

    /** The answer to one request for a replacement. */
    sealed interface Grant {
        /** This is [alias]'s [replacement]th replacement of at most [limit]. */
        data class Granted(
            val replacement: Int,
            val limit: Int,
        ) : Grant

        /** The node has had its [limit] replacements. */
        data class NodeLimitReached(
            val limit: Int,
        ) : Grant

        /** The cluster has made its [limit] replacements. */
        data class ClusterLimitReached(
            val limit: Int,
        ) : Grant
    }

    /** Takes one replacement for [alias] when both limits allow it. */
    fun tryTake(alias: String): Grant =
        lock.withLock {
            val forNode = byAlias.getOrDefault(alias, 0)
            when {
                forNode >= perNodeLimit -> Grant.NodeLimitReached(perNodeLimit)
                used >= clusterLimit -> Grant.ClusterLimitReached(clusterLimit)
                else -> {
                    byAlias[alias] = forNode + 1
                    Grant.Granted(forNode + 1, perNodeLimit)
                }
            }
        }
}
