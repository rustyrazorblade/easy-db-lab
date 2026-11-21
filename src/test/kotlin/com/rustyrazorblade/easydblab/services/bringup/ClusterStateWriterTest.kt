package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Many pipelines write `up`'s state at once: the batched launch, each node's final IPs and each
 * replacement. Whatever the interleaving, the saved file must hold every host, one per alias, in
 * ordinal order, with the last write for an alias winning.
 */
class ClusterStateWriterTest {
    @TempDir
    lateinit var dir: File

    private fun host(
        type: ServerType,
        ordinal: Int,
        instanceId: String,
    ) = ClusterHost(
        publicIp = "54.0.0.$ordinal",
        privateIp = "10.0.0.$ordinal",
        alias = "${type.serverType}$ordinal",
        availabilityZone = "us-west-2a",
        instanceId = instanceId,
    )

    @Test
    fun `concurrent writes from many pipelines keep every host, one per alias, in ordinal order`() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val file = File(dir, "state-$seed.json")
            val manager = ClusterStateManager(file)
            val initial = ClusterState(name = "lab").also(manager::replace)
            val writer = ClusterStateWriter(initial, manager)
            val dbCount = random.nextInt(1, MAX_NODES)
            val appCount = random.nextInt(1, MAX_NODES / 4)
            // Each node is written once at launch and once again (its final IPs or a replacement).
            val writes =
                ((0 until dbCount).map { ServerType.Cassandra to it } + (0 until appCount).map { ServerType.Stress to it })
                    .flatMap { (type, ordinal) -> listOf(Triple(type, ordinal, "i-first"), Triple(type, ordinal, "i-second")) }
            val start = CountDownLatch(1)

            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                // The two writes of one alias stay in order; aliases interleave at random.
                writes.groupBy { it.first to it.second }.values.shuffled(random).forEach { pair ->
                    executor.submit {
                        start.await()
                        pair.forEach { (type, ordinal, id) -> writer.mergeHosts(type, listOf(host(type, ordinal, id))) }
                    }
                }
                start.countDown()
            }

            val saved = manager.load().hosts
            assertThat(saved[ServerType.Cassandra].orEmpty().map { it.alias })
                .containsExactlyElementsOf((0 until dbCount).map { "db$it" })
            assertThat(saved[ServerType.Stress].orEmpty().map { it.alias })
                .containsExactlyElementsOf((0 until appCount).map { "app$it" })
            assertThat(saved.values.flatten()).allMatch { it.instanceId == "i-second" }
        }
    }

    private companion object {
        const val SEEDS = 10
        const val MAX_NODES = 60
    }
}
