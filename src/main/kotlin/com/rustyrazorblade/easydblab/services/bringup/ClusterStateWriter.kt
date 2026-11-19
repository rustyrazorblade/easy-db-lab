package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.mergeByAlias
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The one writer of `up`'s working [state] (design D6 step 6). The batched launch, every node
 * pipeline and every replacement write through it, so a save never loses a host another thread
 * added between its read and its write. Each change is applied and saved under one lock; the save
 * itself is atomic ([ClusterStateManager.save]: a temp file and a rename).
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

    /** Applies [change] to the state and saves it, under the lock. */
    fun update(change: (ClusterState) -> Unit) =
        lock.withLock {
            change(state)
            manager.save(state)
        }

    /** Reads the state under the lock, so [read] never sees a half-applied change. */
    fun <T> read(read: (ClusterState) -> T): T = lock.withLock { read(state) }
}
