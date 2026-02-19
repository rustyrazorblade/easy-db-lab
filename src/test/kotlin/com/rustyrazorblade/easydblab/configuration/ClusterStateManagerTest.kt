package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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
}
