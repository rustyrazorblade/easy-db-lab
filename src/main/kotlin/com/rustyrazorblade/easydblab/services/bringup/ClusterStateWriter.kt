package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.update
import com.rustyrazorblade.easydblab.services.mergeByAlias
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The one writer of `up`'s working [state] (design D6 step 6). The batched launch, every node
 * pipeline and every replacement write through it, so a save never loses a host another thread
 * added between its read and its write. Each change is applied under one lock, to the state on disk
 * through [ClusterStateManager.update] (which keeps what other commands saved meanwhile) and then
 * to [state].
 *
 * It uses a [ReentrantLock], not `synchronized`, because its callers are virtual threads.
 */
class ClusterStateWriter(
    private val state: ClusterState,
    private val manager: ClusterStateManager,
) {
    private val lock = ReentrantLock()

    /**
     * Puts [hosts] into [serverType]'s list by alias: a host with an alias already there replaces
     * it, a new one is added, and the list stays in ordinal order, one entry per alias.
     */
    fun mergeHosts(
        serverType: ServerType,
        hosts: List<ClusterHost>,
    ) = update {
        val all = it.hosts.toMutableMap()
        all[serverType] = mergeByAlias(serverType, all[serverType], hosts)
        it.updateHosts(all)
    }

    /**
     * Applies [change] to the state on disk and to [state], under the lock. The change is applied to
     * a fresh read of `state.json` ([ClusterStateManager.update]), so a change another command saved
     * while `up` ran is kept; it must set values, not step them, because it runs on both copies.
     */
    fun update(change: (ClusterState) -> Unit) =
        lock.withLock {
            manager.update(state, change)
        }

    /** Reads the state under the lock, so [read] never sees a half-applied change. */
    fun <T> read(read: (ClusterState) -> T): T = lock.withLock { read(state) }
}
