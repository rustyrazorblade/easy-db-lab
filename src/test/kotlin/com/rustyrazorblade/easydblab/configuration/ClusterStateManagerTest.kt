package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ClusterStateManagerTest {
    @Test
    fun `incrementStressJobCounter should increment and persist counter`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        // Save initial state
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
            )
        manager.save(state)

        // First increment should return 1
        val first = manager.incrementStressJobCounter()
        assertThat(first).isEqualTo(1)

        // Second increment should return 2
        val second = manager.incrementStressJobCounter()
        assertThat(second).isEqualTo(2)

        // Verify persistence by reloading with a fresh manager
        val freshManager = ClusterStateManager(stateFile)
        val reloaded = freshManager.load()
        assertThat(reloaded.stressJobCounter).isEqualTo(2)
    }

    @Test
    fun `addRunningWorkload adds name and persists`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)
        manager.save(ClusterState(name = "test-cluster", versions = mutableMapOf()))

        manager.addRunningWorkload("clickhouse")
        manager.addRunningWorkload("presto")

        val reloaded = ClusterStateManager(stateFile).load()
        assertThat(reloaded.runningKits).containsExactlyInAnyOrder("clickhouse", "presto")
    }

    @Test
    fun `removeRunningWorkload removes name and persists`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)
        val state = ClusterState(name = "test-cluster", versions = mutableMapOf(), runningKits = mutableSetOf("clickhouse", "presto"))
        manager.save(state)

        manager.removeRunningWorkload("clickhouse")

        val reloaded = ClusterStateManager(stateFile).load()
        assertThat(reloaded.runningKits).containsExactly("presto")
    }

    @Test
    fun `runningKits round-trips through JSON with empty set`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)
        manager.save(ClusterState(name = "test-cluster", versions = mutableMapOf()))

        val reloaded = ClusterStateManager(stateFile).load()
        assertThat(reloaded.runningKits).isEmpty()
    }

    @Test
    fun `incrementStressJobCounter should update lastAccessedAt`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
            )
        manager.save(state)

        val beforeIncrement = manager.load().lastAccessedAt
        Thread.sleep(10)

        manager.incrementStressJobCounter()

        val afterIncrement = manager.load().lastAccessedAt
        assertThat(afterIncrement).isAfter(beforeIncrement)
    }

    /** The name flows raw into config files, Loki file names, S3 keys and labels. */
    @Test
    fun `a state whose cluster name breaks the rule is refused at load, naming the rule`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        ClusterStateManager(stateFile).save(ClusterState(name = "My/Cluster", versions = mutableMapOf()))

        assertThatThrownBy { ClusterStateManager(stateFile).load() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("^[a-z][a-z0-9-]{0,39}$")
    }

    /**
     * `down` records a signal while other threads read the state (`ObservabilityHttp` loads it per
     * request). A reader must see either the old file or the new one, never a half-written one.
     */
    @Test
    fun `a load during repeated saves never reads a torn file`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)
        // A large state makes a non-atomic write take long enough for a reader to catch it midway.
        val big = ClusterState(name = "test-cluster", versions = (1..2000).associate { "k$it" to "v".repeat(40) }.toMutableMap())
        manager.save(big)

        val saving = AtomicBoolean(true)
        val failures = AtomicInteger()
        val reader =
            Thread {
                while (saving.get()) {
                    runCatching { ClusterStateManager(stateFile).load() }.onFailure { failures.incrementAndGet() }
                }
            }.apply { start() }

        repeat(300) { i -> manager.save(big.copy(stressJobCounter = i)) }
        saving.set(false)
        reader.join()

        assertThat(failures.get()).describedAs("loads that read a partial state.json").isZero()
        assertThat(manager.load().stressJobCounter).isEqualTo(299)
        assertThat(tempDir.list().orEmpty().toList()).describedAs("no temp file left behind").containsExactly("state.json")
    }

    @Test
    fun `a save keeps the state file's permissions`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)
        manager.save(ClusterState(name = "test-cluster", versions = mutableMapOf()))
        val chosen = PosixFilePermissions.fromString("rw-r-----")
        Files.setPosixFilePermissions(stateFile.toPath(), chosen)

        manager.save(ClusterState(name = "test-cluster", versions = mutableMapOf(), stressJobCounter = 1))

        assertThat(Files.getPosixFilePermissions(stateFile.toPath())).isEqualTo(chosen)
    }

    @Test
    fun `a first save gives the state file the permissions any new file gets`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val plain = Files.createFile(File(tempDir, "plain").toPath())

        ClusterStateManager(stateFile).save(ClusterState(name = "test-cluster", versions = mutableMapOf()))

        assertThat(Files.getPosixFilePermissions(stateFile.toPath())).isEqualTo(Files.getPosixFilePermissions(plain))
    }
}
