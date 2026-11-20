package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Two commands in one workspace each hold their own copy of the cluster state (issue 986). A change
 * one of them saves must survive the other's save, whatever order their loads and saves run in.
 */
internal class ClusterStateConcurrentUpdateTest {
    @TempDir
    lateinit var workspace: File

    private val stateFile by lazy { File(workspace, CLUSTER_STATE) }

    private fun manager() = ClusterStateManager(stateFile)

    /**
     * The race of issue 986: `spark init` loads the state and works for minutes; `opensearch start`
     * saves its domain meanwhile; then `spark init` saves. The domain must still be there.
     */
    @Test
    fun `issue 986 - a long command's save keeps the domain another command saved while it worked`() {
        manager().replace(ClusterState(name = "qa"))
        val sparkInit = manager()
        val loadedBySpark = sparkInit.load()

        manager().update { it.updateOpenSearchDomain(OpenSearchClusterState(domainName = "qa-os", domainId = "id")) }
        sparkInit.update(loadedBySpark) { it.updateEmrCluster(EMRClusterState(clusterId = "j-1", clusterName = "qa-spark")) }

        val saved = manager().load()
        assertThat(saved.openSearchDomain?.domainName).isEqualTo("qa-os")
        assertThat(saved.emrCluster?.clusterId).isEqualTo("j-1")
        assertThat(loadedBySpark.emrCluster?.clusterId).describedAs("the caller's own copy sees its change").isEqualTo("j-1")
    }

    /**
     * Conservation over every interleaving the scheduler picks: [WRITERS] threads, each with its own
     * manager and its own stale copy, each record [ROUNDS] distinct backup hashes, all released at
     * once. Every hash any writer recorded is in the final file.
     */
    @Test
    fun `concurrent writers that each record their own keys lose none of them`() {
        manager().replace(ClusterState(name = "qa"))
        val start = CyclicBarrier(WRITERS)
        val pool = Executors.newFixedThreadPool(WRITERS)
        try {
            val futures =
                (0 until WRITERS).map { writer ->
                    pool.submit {
                        val own = manager()
                        val stale = own.load()
                        start.await()
                        repeat(ROUNDS) { round ->
                            own.update(stale) { it.backupHashes = it.backupHashes + ("w$writer-$round" to "h") }
                        }
                    }
                }
            futures.forEach { it.get(1, TimeUnit.MINUTES) }
        } finally {
            pool.shutdownNow()
        }

        val expected = (0 until WRITERS).flatMap { w -> (0 until ROUNDS).map { r -> "w$w-$r" } }
        assertThat(manager().load().backupHashes.keys).containsExactlyInAnyOrderElementsOf(expected)
        assertThat(workspace.list().orEmpty().toList())
            .describedAs("no temp file left behind")
            .containsExactlyInAnyOrder(CLUSTER_STATE, Constants.ClusterStateFile.LOCK_FILE)
    }

    /** A counter stepped by many threads at once counts every step: no read-modify-write is lost. */
    @Test
    fun `the stress job counter counts every concurrent increment`() {
        manager().replace(ClusterState(name = "qa"))
        val pool = Executors.newFixedThreadPool(WRITERS)
        try {
            val futures = (0 until WRITERS * ROUNDS).map { pool.submit<Int> { manager().incrementStressJobCounter() } }
            val returned = futures.map { it.get(1, TimeUnit.MINUTES) }
            assertThat(returned).describedAs("each caller gets its own number").doesNotHaveDuplicates()
        } finally {
            pool.shutdownNow()
        }

        assertThat(manager().load().stressJobCounter).isEqualTo(WRITERS * ROUNDS)
    }

    /** A state file that does not parse fails the change with its path, and is left as it was. */
    @Test
    fun `a change to a state file that does not parse fails with the path and writes nothing`() {
        stateFile.writeText("{ not json")

        assertThatThrownBy { manager().update { it.stressJobCounter = 1 } }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining(stateFile.absolutePath)
        assertThat(stateFile.readText()).isEqualTo("{ not json")
    }

    @Test
    fun `a change with no state file fails and creates none`() {
        assertThatThrownBy { manager().update { it.stressJobCounter = 1 } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("easy-db-lab init")
        assertThat(stateFile).doesNotExist()
    }

    @Test
    fun `a change made inside another change fails at once instead of deadlocking`() {
        manager().replace(ClusterState(name = "qa"))

        assertThatThrownBy { manager().update { manager().update { inner -> inner.stressJobCounter = 1 } } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("inside another update")
    }

    private companion object {
        const val WRITERS = 8
        const val ROUNDS = 25
    }
}
