package com.rustyrazorblade.easydblab.output

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConsoleNodeOutputTest {
    // A plain list on purpose: the adapter's lock is the only thing that keeps concurrent writes safe.
    private val out = mutableListOf<String>()
    private val err = mutableListOf<String>()

    private fun output(tailLines: Int = 30) = ConsoleNodeOutput(stdout = { out.add(it) }, stderr = { err.add(it) }, tailLines = tailLines)

    @Test
    fun `a line is tagged with its node`() {
        val nodeOutput = output()
        nodeOutput.line("db0", NodeStream.Stdout, "installing")
        nodeOutput.notice("db0", "uploading a to b")
        assertThat(out).containsExactly("[db0] installing", "[db0] uploading a to b")
        assertThat(err).isEmpty()
    }

    @Test
    fun `a stderr line goes to the error stream`() {
        val nodeOutput = output()
        nodeOutput.line("db1", NodeStream.Stderr, "warning: x")
        assertThat(err).containsExactly("[db1] warning: x")
        assertThat(out).isEmpty()
    }

    @Test
    fun `the tail keeps only the last lines of each node, untagged and in order`() {
        val nodeOutput = output(tailLines = 3)
        (1..5).forEach { nodeOutput.line("db0", NodeStream.Stdout, "a$it") }
        nodeOutput.line("db0", NodeStream.Stderr, "err")
        nodeOutput.line("db1", NodeStream.Stdout, "b1")
        assertThat(nodeOutput.tail("db0")).containsExactly("a4", "a5", "err")
        assertThat(nodeOutput.tail("db1")).containsExactly("b1")
        assertThat(nodeOutput.tail("db9")).isEmpty()
    }

    @Test
    fun `reset forgets one node's tail`() {
        val nodeOutput = output()
        nodeOutput.line("db0", NodeStream.Stdout, "old")
        nodeOutput.line("db1", NodeStream.Stdout, "other")
        nodeOutput.reset("db0")
        nodeOutput.line("db0", NodeStream.Stdout, "new")
        assertThat(nodeOutput.tail("db0")).containsExactly("new")
        assertThat(nodeOutput.tail("db1")).containsExactly("other")
    }

    @Test
    fun `quiet hides lines from the console and still keeps the tail`() {
        val nodeOutput = output()
        nodeOutput.quietly {
            nodeOutput.line("db0", NodeStream.Stdout, "hidden")
            nodeOutput.line("db0", NodeStream.Stderr, "hidden err")
            nodeOutput.notice("db0", "hidden notice")
        }
        nodeOutput.line("db0", NodeStream.Stdout, "shown")
        assertThat(out).containsExactly("[db0] shown")
        assertThat(err).isEmpty()
        assertThat(nodeOutput.tail("db0")).containsExactly("hidden", "hidden err", "hidden notice", "shown")
    }

    @Test
    fun `quietly returns its block's value and restores the mode after an exception`() {
        val nodeOutput = output()
        assertThat(nodeOutput.quietly { 42 }).isEqualTo(42)
        assertThatThrownBy { nodeOutput.quietly { error("boom") } }.hasMessage("boom")
        nodeOutput.line("db0", NodeStream.Stdout, "after")
        assertThat(out).containsExactly("[db0] after")
    }

    @Test
    fun `a nested quietly keeps quiet until the outer one ends`() {
        val nodeOutput = output()
        nodeOutput.quietly {
            nodeOutput.quietly { nodeOutput.line("db0", NodeStream.Stdout, "inner") }
            nodeOutput.line("db0", NodeStream.Stdout, "outer")
        }
        nodeOutput.line("db0", NodeStream.Stdout, "loud")
        assertThat(out).containsExactly("[db0] loud")
    }

    @Test
    fun `passthrough tags each line and keeps no tail`() {
        val nodeOutput = output()
        nodeOutput.passthrough("stress-abc", "one\r\ntwo\n\nfour\n")
        assertThat(out).containsExactly("[stress-abc] one", "[stress-abc] two", "[stress-abc] ", "[stress-abc] four")
        assertThat(nodeOutput.tail("stress-abc")).isEmpty()
    }

    @Test
    fun `lines written from many virtual threads stay whole and in order per node`() {
        val nodes = 32
        val linesPerNode = 500
        val nodeOutput = output()
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            (0 until nodes).forEach { n ->
                executor.submit {
                    (0 until linesPerNode).forEach { i -> nodeOutput.line("node$n", NodeStream.Stdout, "line-$i-node$n") }
                }
            }
            executor.shutdown()
            assertThat(executor.awaitTermination(1, TimeUnit.MINUTES)).isTrue()
        }

        assertThat(out).hasSize(nodes * linesPerNode)
        val pattern = Regex("""^\[node(\d+)] line-(\d+)-node\1$""")
        val byNode = mutableMapOf<String, MutableList<Int>>()
        out.forEach { line ->
            val match = pattern.matchEntire(line) ?: error("not a whole tagged line: $line")
            byNode.getOrPut(match.groupValues[1]) { mutableListOf() }.add(match.groupValues[2].toInt())
        }
        byNode.values.forEach { assertThat(it).isEqualTo((0 until linesPerNode).toList()) }
        (0 until nodes).forEach { n ->
            assertThat(nodeOutput.tail("node$n"))
                .isEqualTo((linesPerNode - 30 until linesPerNode).map { "line-$it-node$n" })
        }
    }
}
