package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ServerType
import org.assertj.core.api.SoftAssertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Invariants of the coordinator (design D6, D7) over a seeded random table of per-node faults:
 * whatever fails where, and in whatever order the pipelines race, every node ends exactly once,
 * no node or cluster passes its replacement limit, each replacement is one launch, a node with no
 * fault is Ready, and the state file holds each alias once, at the instance the node ended on.
 */
class NodeBringUpPropertyTest {
    @TempDir
    lateinit var dir: File

    /** A node's fault: [step] fails on each of its first [badInstances] instances. */
    private data class Fault(
        val step: String,
        val badInstances: Int,
    )

    private fun faults(
        random: Random,
        aliases: List<String>,
    ): Map<String, Fault> =
        aliases
            .filter { random.nextInt(FAULT_ODDS) == 0 }
            .associateWith { Fault(STEPS.random(random), random.nextInt(1, MAX_BAD_INSTANCES + 1)) }

    private fun install(
        h: BringUpHarness,
        faults: Map<String, Fault>,
    ) {
        val seen = ConcurrentHashMap<String, MutableSet<String>>()
        faults.forEach { (alias, fault) ->
            h.steps.scripts["$alias ${fault.step}"] = { host, _ ->
                val instances = seen.computeIfAbsent(alias) { ConcurrentHashMap.newKeySet() }.apply { add(host.instanceId) }
                check(instances.size > fault.badInstances) { "${fault.step} failed on ${host.instanceId}" }
            }
        }
    }

    @Test
    fun `every node ends once and within its replacement limits, whatever fails where`() {
        val softly = SoftAssertions()
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val h = BringUpHarness(File(dir, "seed-$seed").apply { mkdirs() }, random.nextInt(MIN_NODES, MAX_NODES + 1))
            val aliases = h.dbs.map { it.alias }
            val faults = faults(random, aliases)
            install(h, faults)

            val result = h.run()

            val context = "seed $seed, faults $faults"
            softly.assertThat(result.nodes.keys).describedAs(context).containsExactlyInAnyOrderElementsOf(aliases)
            softly
                .assertThat(result.nodes.values.map { it.replacements })
                .describedAs(context)
                .allMatch { it <= Constants.Scale.MAX_NODE_REPLACEMENTS }
            val launches = h.fleet.calls.count { it.startsWith("launch") }
            softly.assertThat(launches).describedAs(context).isEqualTo(result.nodes.values.sumOf { it.replacements })
            softly.assertThat(launches).describedAs(context).isLessThanOrEqualTo(ReplacementBudget(aliases.size).clusterLimit)
            softly
                .assertThat(aliases.filterNot(faults::containsKey).map(result.nodes::getValue))
                .describedAs(context)
                .allMatch { it is NodeEnd.Ready }
            val saved = h.saved(ServerType.Cassandra).associate { it.alias to it.instanceId }
            softly.assertThat(h.saved(ServerType.Cassandra)).describedAs(context).hasSize(aliases.size)
            softly.assertThat(saved).describedAs(context).isEqualTo(result.nodes.mapValues { it.value.host.instanceId })
        }
        softly.assertAll()
    }

    private companion object {
        const val SEEDS = 25
        const val MIN_NODES = 3
        const val MAX_NODES = 12
        const val FAULT_ODDS = 3
        const val MAX_BAD_INSTANCES = 3
        val STEPS = listOf("setup", "registry-tls", "join")
    }
}
