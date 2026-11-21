package com.rustyrazorblade.easydblab.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.io.IOException
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
            )
        manager.replace(state)

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
        manager.replace(ClusterState(name = "test-cluster"))

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
        val state = ClusterState(name = "test-cluster", runningKits = mutableSetOf("clickhouse", "presto"))
        manager.replace(state)

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
        manager.replace(ClusterState(name = "test-cluster"))

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
            )
        manager.replace(state)

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
        ClusterStateManager(stateFile).replace(ClusterState(name = "My/Cluster"))

        assertThatThrownBy { ClusterStateManager(stateFile).load() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("^[a-z][a-z0-9-]{0,39}$")
    }

    @Test
    fun `a state file that cannot be parsed is refused at load, naming the file`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json").apply { writeText("{ not json") }

        assertThatThrownBy { ClusterStateManager(stateFile).load() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(stateFile.absolutePath)
            .hasCauseInstanceOf(IOException::class.java)
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
        val big = ClusterState(name = "test-cluster", nodes = (1..2000).associate { "k$it" to NodeState("v".repeat(40)) }.toMutableMap())
        manager.replace(big)

        val saving = AtomicBoolean(true)
        val failures = AtomicInteger()
        val reader =
            Thread {
                while (saving.get()) {
                    runCatching { ClusterStateManager(stateFile).load() }.onFailure { failures.incrementAndGet() }
                }
            }.apply { start() }

        repeat(300) { i -> manager.replace(big.copy(stressJobCounter = i)) }
        saving.set(false)
        reader.join()

        assertThat(failures.get()).describedAs("loads that read a partial state.json").isZero()
        assertThat(manager.load().stressJobCounter).isEqualTo(299)
        assertThat(tempDir.list().orEmpty().toList())
            .describedAs("no temp file left behind")
            .containsExactlyInAnyOrder("state.json", Constants.ClusterStateFile.LOCK_FILE)
    }

    @Test
    fun `a save keeps the state file's permissions`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)
        manager.replace(ClusterState(name = "test-cluster"))
        val chosen = PosixFilePermissions.fromString("rw-r-----")
        Files.setPosixFilePermissions(stateFile.toPath(), chosen)

        manager.replace(ClusterState(name = "test-cluster", stressJobCounter = 1))

        assertThat(Files.getPosixFilePermissions(stateFile.toPath())).isEqualTo(chosen)
    }

    @Test
    fun `a first save gives the state file the permissions any new file gets`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val plain = Files.createFile(File(tempDir, "plain").toPath())

        ClusterStateManager(stateFile).replace(ClusterState(name = "test-cluster"))

        assertThat(Files.getPosixFilePermissions(stateFile.toPath())).isEqualTo(Files.getPosixFilePermissions(plain))
    }

    @ParameterizedTest(name = "tailscaleActive={0} reads as {1}")
    @CsvSource("true, Tailscale", "false, Socks")
    fun `a state from before the network mode reads its mode from the old Tailscale flag`(
        legacy: Boolean,
        expected: NetworkMode,
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        writeOldState(stateFile) { it.put("tailscaleActive", legacy) }

        assertThat(ClusterStateManager(stateFile).load().networkMode).isEqualTo(expected)
    }

    @Test
    fun `a state with neither field reads as socks`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        writeOldState(stateFile) { }

        assertThat(ClusterStateManager(stateFile).load().networkMode).isEqualTo(NetworkMode.Socks)
    }

    @Test
    fun `a recorded network mode wins over an old Tailscale flag beside it`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        writeOldState(stateFile) {
            it.put("networkMode", "direct")
            it.put("tailscaleActive", true)
        }

        assertThat(ClusterStateManager(stateFile).load().networkMode).isEqualTo(NetworkMode.Direct)
    }

    @Test
    fun `an unknown network mode is refused at load, naming the value and the known ones`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        writeOldState(stateFile) { it.put("networkMode", "vpn") }

        assertThatThrownBy { ClusterStateManager(stateFile).load() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'vpn'")
            .hasMessageContaining("socks, tailscale, direct")
    }

    @Test
    fun `loading an old state writes nothing, and the next save writes the mode and drops the old flag`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        writeOldState(stateFile) { it.put("tailscaleActive", true) }
        val before = stateFile.readText()
        val manager = ClusterStateManager(stateFile)

        val state = manager.load()
        assertThat(stateFile.readText()).isEqualTo(before)
        manager.replace(state)

        val saved = ObjectMapper().readTree(stateFile) as ObjectNode
        assertThat(saved.get("networkMode").asText()).isEqualTo("tailscale")
        assertThat(saved.has("tailscaleActive")).isFalse()
    }

    /** Saves a current state, then rewrites its JSON as an earlier release would have left it. */
    private fun writeOldState(
        stateFile: File,
        change: (ObjectNode) -> Unit,
    ) {
        ClusterStateManager(stateFile).replace(ClusterState(name = "test-cluster"))
        val mapper = ObjectMapper()
        val tree = mapper.readTree(stateFile) as ObjectNode
        tree.remove("networkMode")
        change(tree)
        mapper.writerWithDefaultPrettyPrinter().writeValue(stateFile, tree)
    }
}
