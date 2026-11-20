package com.rustyrazorblade.easydblab.configuration

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.rustyrazorblade.easydblab.Constants
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages persistence of ClusterState to/from disk.
 *
 * This separates the data (ClusterState) from persistence concerns,
 * making ClusterState a pure data class that's easier to test.
 *
 * Several commands can run in one workspace at once (`spark init` while `opensearch start` runs),
 * each with its own copy of the state. None of them writes its whole copy back. A change goes
 * through [update], which reads the file again under an exclusive lock on
 * [Constants.ClusterStateFile.LOCK_FILE], applies only that change to what it read, and renames the
 * result into place, so no command loses a change another one saved. A long command holds the
 * lock only for that read, apply and rename, never while it works. A JVM cannot take a second lock
 * on a file it has locked already, so the threads of one process also take turns on [localLock]
 * first. Readers ([load]) take no lock: the rename gives them a whole file.
 *
 * @param stateFile The file to read/write cluster state. Defaults to "state.json" in current directory.
 * @param lockTimeout how long a change waits for another process to release the lock file
 */
class ClusterStateManager(
    private val stateFile: File = File(CLUSTER_STATE),
    private val lockTimeout: Duration = Constants.ClusterStateFile.LOCK_TIMEOUT,
) {
    private val lockFile = File(stateFile.absoluteFile.parentFile, Constants.ClusterStateFile.LOCK_FILE)

    private val mapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .apply {
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
                configure(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, false)
                disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            }

    /**
     * Load cluster state from the configured file.
     *
     * A saved [InitConfig] that records no `cni` predates Cilium, so its cluster runs Flannel. The
     * missing field is read as [CniMode.Flannel] rather than filled from the constructor default
     * ([CniMode.Cilium]), which would render Cilium scrape jobs on a Flannel cluster.
     *
     * A state from before [ClusterState.networkMode] records `tailscaleActive` instead: `true` reads
     * as [NetworkMode.Tailscale], and `false` or no field as [NetworkMode.Socks]. When `networkMode` is
     * present, a `tailscaleActive` beside it is ignored. Loading writes nothing; the next [update] writes
     * `networkMode` and no `tailscaleActive`, which the class no longer has.
     *
     * @throws IllegalStateException if `networkMode` is not a known mode, naming the value and the known ones
     * @throws Exception if file doesn't exist or can't be parsed
     */
    fun load(): ClusterState {
        val tree = mapper.readTree(stateFile)
        val initConfig = tree.get("initConfig")
        if (initConfig is ObjectNode && !initConfig.has(CNI_FIELD)) {
            initConfig.put(CNI_FIELD, CniMode.Flannel.name)
        }
        if (tree is ObjectNode) migrateNetworkMode(tree)
        val state = mapper.treeToValue(tree, ClusterState::class.java)
        // The name flows raw into config files, Loki index file names, S3 keys and labels; a
        // hand-edited or pre-rule state must not reach any of them.
        check(Regex(Constants.Observability.CLUSTER_NAME_PATTERN).matches(state.name)) {
            "Invalid cluster name '${state.name}' in state.json: a cluster name must match " +
                Constants.Observability.CLUSTER_NAME_PATTERN
        }
        return state
    }

    /**
     * Writes [state] as the whole cluster state, replacing whatever the file holds.
     *
     * Only a command that starts a workspace's state from nothing uses this: `init`, and a restore
     * that found no `state.json` in the backup. Every other change goes through [update], which keeps
     * the changes other commands saved after this one loaded the state.
     *
     * @throws IOException when another process holds the lock for longer than
     *   [Constants.ClusterStateFile.LOCK_TIMEOUT]
     */
    fun replace(state: ClusterState) = locked { write(state) }

    /**
     * Applies [change] to the cluster state as it is on disk now, and saves the result.
     *
     * Under the lock this reads `state.json` again, applies [change] to that fresh copy, and renames
     * the new file into place, so a change another command saved after this one loaded the state is
     * kept. [change] must set only what this command changed, as a function of the state it is given,
     * and must be quick: the lock is held while it runs.
     *
     * @return the state as saved
     * @throws IllegalStateException when there is no state file, or when called from inside another
     *   update on the same thread
     * @throws IOException when the state file cannot be read or parsed, or when another process holds
     *   the lock for longer than [Constants.ClusterStateFile.LOCK_TIMEOUT]; nothing is written then
     */
    fun update(change: (ClusterState) -> Unit): ClusterState =
        locked {
            check(stateFile.exists()) { "No cluster state at ${stateFile.absolutePath}: run 'easy-db-lab init' first" }
            val fresh = loadForUpdate()
            change(fresh)
            write(fresh)
            fresh
        }

    /**
     * Check if the state file exists.
     */
    fun exists(): Boolean = stateFile.exists()

    /**
     * Atomically increment the stress job counter and return the new value.
     * Used for unique job naming and port assignment.
     */
    fun incrementStressJobCounter(): Int =
        update {
            it.stressJobCounter++
            it.lastAccessedAt = Instant.now()
        }.stressJobCounter

    /** Adds [name] to the running kits, keeping every other change on disk. */
    fun addRunningWorkload(name: String) {
        update {
            it.runningKits = it.runningKits + name
            it.lastAccessedAt = Instant.now()
        }
    }

    /** Removes [name] from the running kits, keeping every other change on disk. */
    fun removeRunningWorkload(name: String) {
        update {
            it.runningKits = it.runningKits - name
            it.lastAccessedAt = Instant.now()
        }
    }

    private fun loadForUpdate(): ClusterState =
        try {
            load()
        } catch (e: JsonProcessingException) {
            throw IOException(
                "Cannot parse the cluster state at ${stateFile.absolutePath}, so nothing was saved: ${e.originalMessage}",
                e,
            )
        }

    /**
     * Writes [state] to a temp file in the same directory, which is then renamed over the state file
     * in one atomic step, so a reader sees the old file or the new one, never a half-written one. The
     * temp file takes the state file's permissions, or those of any new file when there is none yet,
     * so a write never changes them.
     */
    private fun write(state: ClusterState) {
        val target = stateFile.absoluteFile.toPath()
        val temp = Files.createFile(target.resolveSibling("${target.fileName}.${UUID.randomUUID()}.tmp"))
        try {
            if (Files.exists(target) && "posix" in target.fileSystem.supportedFileAttributeViews()) {
                Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target))
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), state)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * Runs [block] while this thread holds [localLock] and this process holds the lock file. A nested
     * call fails at once with a clear message, rather than with an `OverlappingFileLockException`
     * from inside the file channel.
     */
    private fun <T> locked(block: () -> T): T {
        check(!localLock.isHeldByCurrentThread) {
            "A cluster state update ran inside another update; apply both changes in one update"
        }
        return localLock.withLock {
            FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                acquire(channel).use { block() }
            }
        }
    }

    /** Takes the exclusive lock on [channel], trying again until [lockTimeout] passes. */
    private fun acquire(channel: FileChannel): FileLock {
        val interval = Constants.ClusterStateFile.LOCK_RETRY_INTERVAL
        val timeout = lockTimeout
        val config =
            RetryConfig
                .custom<FileLock?>()
                .maxAttempts((timeout.toMillis() / interval.toMillis()).toInt() + 1)
                .waitDuration(interval)
                .retryOnResult { it == null }
                .build()
        return Retry.of("cluster-state-lock", config).executeSupplier { channel.tryLock() }
            ?: throw IOException(
                "Another easy-db-lab command held ${lockFile.absolutePath} for more than ${timeout.toMillis()}ms, " +
                    "so the cluster state was not changed. Run the command again once the other one is done.",
            )
    }

    /**
     * Rewrites [tree] so it holds a known `networkMode` and no `tailscaleActive`: an absent mode is
     * taken from the old boolean, and an unknown one fails the load.
     */
    private fun migrateNetworkMode(tree: ObjectNode) {
        val legacyTailscale = tree.remove(LEGACY_TAILSCALE_FIELD)?.asBoolean(false) ?: false
        val recorded = tree.get(NETWORK_MODE_FIELD)
        if (recorded == null || recorded.isNull) {
            val mode = if (legacyTailscale) NetworkMode.Tailscale else NetworkMode.Socks
            tree.put(NETWORK_MODE_FIELD, mode.id)
            return
        }
        val id = recorded.asText()
        checkNotNull(NetworkMode.fromId(id)) {
            "Unknown $NETWORK_MODE_FIELD '$id' in $CLUSTER_STATE: the known values are ${NetworkMode.knownIds}"
        }
    }

    private companion object {
        /** The [InitConfig] field that records the cluster's CNI. */
        const val CNI_FIELD = "cni"

        /** The [ClusterState] field that records the network mode. */
        const val NETWORK_MODE_FIELD = "networkMode"

        /** The boolean that recorded Tailscale before [NETWORK_MODE_FIELD]; read only from old states. */
        const val LEGACY_TAILSCALE_FIELD = "tailscaleActive"

        /** Taken before the file lock, by every thread of this JVM, for every workspace. */
        val localLock = ReentrantLock()
    }
}

/**
 * Applies [change] to the state on disk ([ClusterStateManager.update]) and then to [local], the
 * caller's own copy, so the code after it reads its own change. The copy on disk also keeps every
 * change another command saved; [local] does not gain those. [change] runs twice, once on each
 * copy, so it must set values (`it.vpcId = id`), not step them (`it.counter++`).
 */
fun ClusterStateManager.update(
    local: ClusterState,
    change: (ClusterState) -> Unit,
) {
    update(change)
    change(local)
}
