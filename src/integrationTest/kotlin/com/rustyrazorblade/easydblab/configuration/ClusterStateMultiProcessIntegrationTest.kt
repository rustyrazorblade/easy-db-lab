package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Issue 986 across real processes: two easy-db-lab commands in one workspace are two JVMs, and only
 * the file lock keeps them from losing each other's changes. The in-JVM lock plays no part here.
 */
internal class ClusterStateMultiProcessIntegrationTest {
    @TempDir
    lateinit var workspace: File

    private val stateFile by lazy { File(workspace, CLUSTER_STATE) }

    /** Conservation: every key each process recorded, from its own stale copy, is in the final file. */
    @Test
    fun `writers in separate processes that interleave their saves lose none of each other's keys`() {
        ClusterStateManager(stateFile).replace(ClusterState(name = "qa"))
        val writers = (0 until PROCESSES).map { "p$it" }
        val children = writers.map { child("record", stateFile.absolutePath, it, ROUNDS.toString()) }

        // Every child holds its stale copy before any of them saves, then all start at once.
        awaitFiles(writers.map { File(workspace, "${ClusterStateWriterProcess.LOADED_PREFIX}$it") })
        File(workspace, ClusterStateWriterProcess.START_FILE).createNewFile()
        children.forEach { process ->
            assertThat(process.waitFor(2, TimeUnit.MINUTES)).describedAs("child finished").isTrue()
            assertThat(process.exitValue()).describedAs(process.inputStream.bufferedReader().readText()).isZero()
        }

        val expected = writers.flatMap { w -> (0 until ROUNDS).map { "$w-$it" } }
        assertThat(ClusterStateManager(stateFile).load().backupHashes.keys).containsExactlyInAnyOrderElementsOf(expected)
        assertThat(workspace.list().orEmpty().filter { it.endsWith(".tmp") }).describedAs("no temp file left behind").isEmpty()
    }

    /** A process that holds the lock past the timeout fails the change, naming the lock file, and nothing is written. */
    @Test
    fun `a change fails with the lock file named when another process never releases it`() {
        ClusterStateManager(stateFile).replace(ClusterState(name = "qa"))
        val before = stateFile.readText()
        val ready = File(workspace, "held")
        val holder = child("hold", stateFile.absolutePath, ready.absolutePath)
        try {
            awaitFiles(listOf(ready))

            assertThatThrownBy {
                ClusterStateManager(stateFile, lockTimeout = Duration.ofMillis(300)).update { it.stressJobCounter = 7 }
            }.isInstanceOf(IOException::class.java)
                .hasMessageContaining(Constants.ClusterStateFile.LOCK_FILE)
            assertThat(stateFile.readText()).isEqualTo(before)
        } finally {
            holder.outputStream.close()
            holder.waitFor(1, TimeUnit.MINUTES)
        }

        ClusterStateManager(stateFile).update { it.stressJobCounter = 7 }
        assertThat(ClusterStateManager(stateFile).load().stressJobCounter).describedAs("released lock is taken").isEqualTo(7)
    }

    private fun awaitFiles(files: List<File>) {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1)
        while (files.any { !it.exists() } && System.nanoTime() < deadline) Thread.sleep(POLL_MILLIS)
        assertThat(files).describedAs("every child reached its marker").allMatch { it.exists() }
    }

    private fun child(vararg args: String): Process {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val command = listOf(java, "-cp", System.getProperty("java.class.path"), ClusterStateWriterProcess::class.java.name) + args
        return ProcessBuilder(command).directory(workspace).redirectErrorStream(true).start()
    }

    private companion object {
        const val PROCESSES = 4
        const val ROUNDS = 40
        const val POLL_MILLIS = 20L
    }
}
