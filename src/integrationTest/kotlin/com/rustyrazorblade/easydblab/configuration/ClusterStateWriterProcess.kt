package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * The child JVM of [ClusterStateMultiProcessIntegrationTest]: a stand-in for one easy-db-lab
 * command that changes the workspace's cluster state.
 *
 * `record <state.json> <writer> <rounds>` loads the state once, waits for a start file, then
 * records `rounds` backup hashes through [ClusterStateManager.update], each from its stale copy.
 * `hold <state.json> <ready file>` takes the state lock, creates the ready file and holds the lock
 * until stdin closes.
 */
internal object ClusterStateWriterProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val stateFile = File(args[1])
        when (args[0]) {
            "record" -> record(stateFile, writer = args[2], rounds = args[3].toInt())
            "hold" -> hold(stateFile, ready = File(args[2]))
            else -> error("unknown mode ${args[0]}")
        }
    }

    private fun record(
        stateFile: File,
        writer: String,
        rounds: Int,
    ) {
        val manager = ClusterStateManager(stateFile)
        val stale = manager.load()
        File(stateFile.parentFile, "$LOADED_PREFIX$writer").createNewFile()
        val go = File(stateFile.parentFile, START_FILE)
        while (!go.exists()) Thread.sleep(POLL_MILLIS)
        repeat(rounds) { round ->
            manager.update(stale) { it.backupHashes = it.backupHashes + ("$writer-$round" to "h") }
        }
    }

    private fun hold(
        stateFile: File,
        ready: File,
    ) {
        val lockFile = File(stateFile.parentFile, Constants.ClusterStateFile.LOCK_FILE)
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                ready.createNewFile()
                System.`in`.read()
            }
        }
    }

    /** The file whose appearance releases every `record` child at once. */
    const val START_FILE = "start"

    /** Each `record` child creates `loaded-<writer>` once it holds its stale copy. */
    const val LOADED_PREFIX = "loaded-"

    private const val POLL_MILLIS = 5L
}
