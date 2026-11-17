package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
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
 * fault is Ready, a node whose image lacks what the cluster needs fails with no replacement, every
 * watched instance is unwatched, and the state file holds each alias once, at the instance the
 * node ended on. Faults fail a step, end the instance mid-setup, or are an image defect.
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
            if (fault.step == IMAGE) {
                val report = Event.Cilium.NodeImageMissingFixes(listOf(alias), listOf("fix"))
                h.steps.imageDefects[alias] = ImageDefect("its image lacks a fix", report)
                return@forEach
            }
            val step = if (fault.step == ENDED) "setup" else fault.step
            h.steps.scripts["$alias $step"] = { host, _ ->
                val instances = seen.computeIfAbsent(alias) { ConcurrentHashMap.newKeySet() }.apply { add(host.instanceId) }
                if (instances.size <= fault.badInstances) {
                    if (fault.step == ENDED) {
                        h.tracker.end(host.instanceId)
                        BringUpHarness.hang()
                    }
                    error("${fault.step} failed on ${host.instanceId}")
                }
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
            softly
                .assertThat(faults.filterValues { it.step == IMAGE }.keys.map(result.nodes::getValue))
                .describedAs(context)
                .allMatch { it is NodeEnd.Failed && it.replacements == 0 }
            softly.assertThat(h.tracker.unwatched).describedAs(context).containsAll(h.tracker.watched)
        }
        softly.assertAll()
    }

    private companion object {
        const val SEEDS = 25
        const val MIN_NODES = 3
        const val MAX_NODES = 12
        const val FAULT_ODDS = 3
        const val MAX_BAD_INSTANCES = 3
        const val ENDED = "ended"
        const val IMAGE = "image"

        /** The step each fault breaks; [ENDED] ends the instance mid-setup, [IMAGE] is a defect no replacement fixes. */
        val STEPS = listOf("setup", "registry-tls", "join", ENDED, IMAGE)
    }
}
