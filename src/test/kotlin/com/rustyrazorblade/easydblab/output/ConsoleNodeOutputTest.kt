package com.rustyrazorblade.easydblab.output

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ConsoleNodeOutputTest {
    // A plain list on purpose: the adapter's lock is the only thing that keeps concurrent writes safe.
    private val out = mutableListOf<String>()
    private val err = mutableListOf<String>()

    private fun output(tailLines: Int = Constants.NodeOutput.TAIL_LINES) =
        ConsoleNodeOutput(stdout = { out.add(it) }, stderr = { err.add(it) }, tailLines = tailLines)

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

    /** At 80 nodes `db7`, `app12` and `control0` interleave; padded tags keep their text in one column. */
    @Test
    fun `tags are padded to the longest alias seen so far`() {
        val nodeOutput = output()
        nodeOutput.line("db0", NodeStream.Stdout, "one")
        nodeOutput.line("control0", NodeStream.Stdout, "two")
        nodeOutput.line("db0", NodeStream.Stdout, "three")
        assertThat(out).containsExactly("[db0] one", "[control0] two", "[db0]      three")
    }

    @Test
    fun `the tail keeps only the last lines of each node, untagged, in order, with stderr marked`() {
        val nodeOutput = output(tailLines = 3)
        (1..5).forEach { nodeOutput.line("db0", NodeStream.Stdout, "a$it") }
        nodeOutput.line("db0", NodeStream.Stderr, "err")
        nodeOutput.line("db1", NodeStream.Stdout, "b1")
        assertThat(nodeOutput.tail("db0")).containsExactly("a4", "a5", "stderr: err")
        assertThat(nodeOutput.tail("db1")).containsExactly("b1")
        assertThat(nodeOutput.tail("db9")).isEmpty()
    }

    @Test
    fun `a default-built adapter keeps the last TAIL_LINES of a node that printed 100`() {
        val nodeOutput = ConsoleNodeOutput(stdout = { out.add(it) }, stderr = { err.add(it) })
        (1..100).forEach { nodeOutput.line("db0", NodeStream.Stdout, "line $it") }
        val kept = Constants.NodeOutput.TAIL_LINES
        assertThat(nodeOutput.tail("db0")).containsExactlyElementsOf((101 - kept..100).map { "line $it" })
    }

    @Test
    fun `a long line is cut in the tail and printed whole`() {
        val nodeOutput = output()
        val long = "x".repeat(Constants.NodeOutput.TAIL_ENTRY_CHARS + 10)
        nodeOutput.line("db0", NodeStream.Stdout, long)
        assertThat(out).containsExactly("[db0] $long")
        assertThat(nodeOutput.tail("db0").single())
            .isEqualTo("x".repeat(Constants.NodeOutput.TAIL_ENTRY_CHARS) + Constants.NodeOutput.TAIL_ENTRY_ELLIPSIS)
    }

    @Test
    fun `a notice is printed and not kept, a mark is printed and kept`() {
        val nodeOutput = output()
        nodeOutput.notice("db0", "installing /etc/x (mode 0644)")
        nodeOutput.mark("db0", "setup attempt 2")
        nodeOutput.line("db0", NodeStream.Stdout, "script line")
        assertThat(out).containsExactly("[db0] installing /etc/x (mode 0644)", "[db0] setup attempt 2", "[db0] script line")
        assertThat(nodeOutput.tail("db0")).containsExactly("setup attempt 2", "script line")
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

    /** An attempt left behind on an ended instance can still print; its lines must not enter the replacement's tail. */
    @Test
    fun `a line from an earlier instance is printed but not kept in the new instance's tail`() {
        val nodeOutput = output()
        nodeOutput.reset("db0", "i-old")
        nodeOutput.line("db0", NodeStream.Stdout, "old setup", instance = "i-old")
        nodeOutput.reset("db0", "i-new")
        nodeOutput.line("db0", NodeStream.Stdout, "late line", instance = "i-old")
        nodeOutput.line("db0", NodeStream.Stdout, "new setup", instance = "i-new")
        assertThat(out).containsExactly("[db0] old setup", "[db0] late line", "[db0] new setup")
        assertThat(nodeOutput.tail("db0")).containsExactly("new setup")
    }

    @Test
    fun `a line from an earlier instance is not printed while quiet`() {
        val nodeOutput = output()
        nodeOutput.reset("db0", "i-new")
        nodeOutput.quietly { nodeOutput.line("db0", NodeStream.Stdout, "late line", instance = "i-old") }
        assertThat(out).isEmpty()
        assertThat(nodeOutput.tail("db0")).isEmpty()
    }

    @Test
    fun `a line with no instance id is kept in the current tail`() {
        val nodeOutput = output()
        nodeOutput.reset("db0", "i-new")
        nodeOutput.line("db0", NodeStream.Stdout, "from a host with no id")
        assertThat(nodeOutput.tail("db0")).containsExactly("from a host with no id")
    }

    @Test
    fun `every line is redacted, passthrough lines too`() {
        val nodeOutput = output()
        nodeOutput.line("db0", NodeStream.Stdout, "clone https://alice:hunter2@git.example.com/r.git")
        nodeOutput.passthrough("stress-abc", "Authorization: Bearer abc.def\nTOKEN=xyz")
        assertThat(out).noneMatch { "hunter2" in it || "abc.def" in it || "xyz" in it }
        assertThat(nodeOutput.tail("db0").single()).doesNotContain("hunter2")
    }

    @Test
    fun `quiet hides lines from the console and still keeps the tail`() {
        val nodeOutput = output()
        nodeOutput.quietly {
            nodeOutput.line("db0", NodeStream.Stdout, "hidden")
            nodeOutput.line("db0", NodeStream.Stderr, "hidden err")
            nodeOutput.mark("db0", "hidden mark")
        }
        nodeOutput.line("db0", NodeStream.Stdout, "shown")
        assertThat(out).containsExactly("[db0] shown")
        assertThat(err).isEmpty()
        assertThat(nodeOutput.tail("db0")).containsExactly("hidden", "stderr: hidden err", "hidden mark", "shown")
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

    /**
     * Two threads enter quietly, and the first to enter leaves first. A saved-and-restored flag
     * would turn quiet off when the first left (it saw "not quiet"), under the second.
     */
    @Test
    fun `two overlapping quietly calls keep quiet until the last one ends`() {
        val nodeOutput = output()
        val firstEntered = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val firstLeft = CountDownLatch(1)
        val secondMayLeave = CountDownLatch(1)

        val first =
            Thread.ofVirtual().start {
                nodeOutput.quietly {
                    firstEntered.countDown()
                    secondEntered.await()
                }
                firstLeft.countDown()
            }
        val second =
            Thread.ofVirtual().start {
                firstEntered.await()
                nodeOutput.quietly {
                    secondEntered.countDown()
                    secondMayLeave.await()
                }
            }
        firstLeft.await()
        nodeOutput.line("db0", NodeStream.Stdout, "while the second is still quiet")
        secondMayLeave.countDown()
        first.join()
        second.join()
        nodeOutput.line("db0", NodeStream.Stdout, "after both")

        assertThat(out).containsExactly("[db0] after both")
    }

    @Test
    fun `passthrough tags each line and keeps no tail`() {
        val nodeOutput = output()
        nodeOutput.passthrough("stress-abc", "one\r\ntwo\n\nfour\n")
        assertThat(out).containsExactly("[stress-abc] one", "[stress-abc] two", "[stress-abc] ", "[stress-abc] four")
        assertThat(nodeOutput.tail("stress-abc")).isEmpty()
    }

    /**
     * The console callback flags itself busy for each write and yields in the middle, so two
     * writes that overlapped (no lock, or a lock released mid-write) are caught, not just likely.
     */
    @Test
    fun `lines written from many virtual threads never overlap, stay whole and keep each node's order`() {
        val nodes = 32
        val linesPerNode = 500
        val busy = AtomicBoolean(false)
        val overlaps = AtomicInteger()
        val nodeOutput =
            ConsoleNodeOutput(
                stdout = {
                    if (!busy.compareAndSet(false, true)) overlaps.incrementAndGet()
                    Thread.yield()
                    out.add(it)
                    busy.set(false)
                },
                stderr = { err.add(it) },
            )
        // Every alias is the same length, so no tag is padded.
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            (0 until nodes).forEach { n ->
                executor.submit {
                    (0 until linesPerNode).forEach { i -> nodeOutput.line(alias(n), NodeStream.Stdout, "line-$i-${alias(n)}") }
                }
            }
            executor.shutdown()
            assertThat(executor.awaitTermination(1, TimeUnit.MINUTES)).isTrue()
        }

        assertThat(overlaps.get()).isZero()
        assertThat(out).hasSize(nodes * linesPerNode)
        val pattern = Regex("""^\[(node\d+)] line-(\d+)-\1$""")
        val byNode = mutableMapOf<String, MutableList<Int>>()
        out.forEach { line ->
            val match = pattern.matchEntire(line) ?: error("not a whole tagged line: $line")
            byNode.getOrPut(match.groupValues[1]) { mutableListOf() }.add(match.groupValues[2].toInt())
        }
        byNode.values.forEach { assertThat(it).isEqualTo((0 until linesPerNode).toList()) }
        val kept = Constants.NodeOutput.TAIL_LINES
        (0 until nodes).forEach { n ->
            assertThat(nodeOutput.tail(alias(n))).isEqualTo((linesPerNode - kept until linesPerNode).map { "line-$it-${alias(n)}" })
        }
    }

    private fun alias(n: Int) = "node%02d".format(n)
}
