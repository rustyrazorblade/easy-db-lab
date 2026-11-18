package com.rustyrazorblade.easydblab.output

import com.rustyrazorblade.easydblab.Constants
import java.util.ArrayDeque
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
 * @param stdout prints a stdout or notice line; `println` by default
 * @param stderr prints a stderr line; standard error by default, as for error events
 * @param tailLines how many lines are kept per node
 */
class ConsoleNodeOutput(
    private val stdout: (String) -> Unit = { println(it) },
    private val stderr: (String) -> Unit = { System.err.println(it) },
    private val tailLines: Int = Constants.NodeOutput.TAIL_LINES,
) : NodeOutput {
    private val lock = ReentrantLock()
    private val tails = HashMap<String, ArrayDeque<String>>()

    @Volatile
    private var quiet = false

    override fun line(
        node: String,
        stream: NodeStream,
        text: String,
    ) {
        val target = if (stream == NodeStream.Stderr) stderr else stdout
        write(node, text, target, keep = true)
    }

    override fun notice(
        node: String,
        text: String,
    ) = write(node, text, stdout, keep = true)

    override fun passthrough(
        source: String,
        text: String,
    ) {
        // A trailing newline ends the last line; it does not start an empty one.
        val lines = text.lines()
        val complete = if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines
        complete.forEach { write(source, it, stdout, keep = false) }
    }

    override fun tail(node: String): List<String> = lock.withLock { tails[node]?.toList() ?: emptyList() }

    override fun reset(node: String) {
        lock.withLock { tails.remove(node) }
    }

    override fun <T> quietly(block: () -> T): T {
        val previous = quiet
        quiet = true
        try {
            return block()
        } finally {
            quiet = previous
        }
    }

    private fun write(
        node: String,
        text: String,
        target: (String) -> Unit,
        keep: Boolean,
    ) {
        lock.withLock {
            if (keep) record(node, text)
            if (!quiet) target("[$node] $text")
        }
    }

    private fun record(
        node: String,
        text: String,
    ) {
        val tail = tails.getOrPut(node) { ArrayDeque(tailLines) }
        if (tail.size == tailLines) tail.removeFirst()
        tail.addLast(text)
    }
}
