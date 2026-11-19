package com.rustyrazorblade.easydblab.output

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.redact
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The console adapter of [NodeOutput]: prints each line as `[node] text` and keeps a bounded tail
 * per node.
 *
 * Many nodes write at once during bring-up, so one lock guards each console write and its tail
 * update: a line is one write, and no other node's line can land inside it.  The lock is a
 * [ReentrantLock], not `synchronized`, because bring-up runs on virtual threads and a virtual
 * thread blocked in a `synchronized` block pins its carrier.  The lock only ever covers the write
 * of a line that is already complete.  Remote output reaches it from a per-command thread
 * (`ssh/OffThreadOutputSink`), never from an SSH I/O thread, so a slow console or a held lock
 * delays printing but never a remote read.
 *
 * Each tag is padded to the longest alias seen so far, so the text of many nodes lines up.  A tail
 * entry is cut to [Constants.NodeOutput.TAIL_ENTRY_CHARS] and a stderr entry is marked; the console
 * gets the whole line.  Each node's tail belongs to the instance its last [reset] named.
 *
 * @param stdout prints a stdout, mark or notice line; `println` by default
 * @param stderr prints a stderr line; standard error by default, as for error events
 * @param tailLines how many lines are kept per node
 */
class ConsoleNodeOutput(
    private val stdout: (String) -> Unit = { println(it) },
    private val stderr: (String) -> Unit = { System.err.println(it) },
    private val tailLines: Int = Constants.NodeOutput.TAIL_LINES,
) : NodeOutput {
    private val lock = ReentrantLock()
    private val tails = HashMap<String, Tail>()
    private val quietDepth = AtomicInteger()
    private var tagWidth = 0

    override fun line(
        node: String,
        stream: NodeStream,
        text: String,
        instance: String,
    ) {
        val target = if (stream == NodeStream.Stderr) stderr else stdout
        val prefix = if (stream == NodeStream.Stderr) Constants.NodeOutput.TAIL_STDERR_PREFIX else ""
        write(node, text, target, Keep.Line(instance, prefix))
    }

    override fun notice(
        node: String,
        text: String,
    ) = write(node, text, stdout, Keep.Nothing)

    override fun mark(
        node: String,
        text: String,
    ) = write(node, text, stdout, Keep.Line("", ""))

    override fun passthrough(
        source: String,
        text: String,
    ) {
        // A trailing newline ends the last line; it does not start an empty one.
        val lines = text.lines()
        val complete = if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines
        complete.forEach { write(source, it, stdout, Keep.Nothing) }
    }

    override fun tail(node: String): List<String> = lock.withLock { tails[node]?.lines?.toList() ?: emptyList() }

    override fun reset(
        node: String,
        instance: String,
    ) {
        lock.withLock { tails[node] = Tail(instance) }
    }

    override fun <T> quietly(block: () -> T): T {
        quietDepth.incrementAndGet()
        try {
            return block()
        } finally {
            quietDepth.decrementAndGet()
        }
    }

    private fun write(
        node: String,
        text: String,
        target: (String) -> Unit,
        keep: Keep,
    ) {
        val clean = redact(text)
        lock.withLock {
            if (keep is Keep.Line) record(node, clean, keep)
            // Quiet hides every line, a stale instance's too: only the tail of the current one matters then.
            if (quietDepth.get() == 0) target("${tag(node)} $clean")
        }
    }

    /** Records [text] in [node]'s tail, unless it came from an instance other than the tail's. */
    private fun record(
        node: String,
        text: String,
        keep: Keep.Line,
    ) {
        val tail = tails.getOrPut(node) { Tail("") }
        if (keep.instance.isNotEmpty() && tail.instance.isNotEmpty() && keep.instance != tail.instance) return
        if (tail.lines.size == tailLines) tail.lines.removeFirst()
        tail.lines.addLast(entry(keep.prefix + text))
    }

    private fun entry(text: String): String =
        if (text.length <= Constants.NodeOutput.TAIL_ENTRY_CHARS) {
            text
        } else {
            text.take(Constants.NodeOutput.TAIL_ENTRY_CHARS) + Constants.NodeOutput.TAIL_ENTRY_ELLIPSIS
        }

    private fun tag(node: String): String {
        tagWidth = maxOf(tagWidth, node.length)
        return "[$node]".padEnd(tagWidth + 2)
    }

    /** One node's kept lines, and the instance they belong to (empty: any). */
    private class Tail(
        val instance: String,
    ) {
        val lines = ArrayDeque<String>()
    }

    /** Whether a written line is kept in its node's tail, and how. */
    private sealed interface Keep {
        /** Kept, when [instance] is the tail's (or either is empty), as [prefix] and the text. */
        data class Line(
            val instance: String,
            val prefix: String,
        ) : Keep

        /** Printed only. */
        data object Nothing : Keep
    }
}
