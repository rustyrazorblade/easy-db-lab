package com.rustyrazorblade.easydblab.ssh

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.output.NodeStream
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue

/**
 * Hands each line to [sink] on a thread of its own, never on the thread that writes it.
 *
 * MINA writes a command's output into its streams on its shared NIO worker threads, which every
 * SSH session in the process uses.  The real sink takes the console lock and prints, so calling
 * it there would let a slow terminal, or a lock held by another node's line, stall every session's
 * reads.  [line] only queues the line on an unbounded queue, which never blocks and never throws on
 * an interrupt; one virtual thread per command takes
 * the lines in the order they were written and calls [sink].
 *
 * A failing sink cannot reach MINA: its exception is logged on the drain thread and the next line
 * is still delivered.  [close] waits until every queued line has been handed over, so a command's
 * output is all printed before the command returns.  On an interrupted thread it waits at most
 * [interruptedWait], so a stalled console cannot hold an abandoned worker.
 */
class OffThreadOutputSink(
    private val interruptedWait: Duration = Constants.NodeOutput.INTERRUPTED_DRAIN_WAIT,
    private val sink: RemoteOutputSink,
) : RemoteOutputSink,
    AutoCloseable {
    private val queue = LinkedBlockingQueue<Item>()
    private val drain = Thread.ofVirtual().name("ssh-output").start(::deliver)
    private var closed = false

    override fun line(
        stream: NodeStream,
        text: String,
    ) {
        queue.offer(Item.Line(stream, text))
    }

    override fun close() {
        if (closed) return
        closed = true
        queue.offer(Item.End)
        awaitDrain()
    }

    /**
     * Waits for the drain thread to finish, then restores any interrupt. An abandoned worker is
     * interrupted while its command runs; throwing here would replace the command's own failure
     * with an [InterruptedException] and lose its last lines. So an interrupt does not end the wait
     * at once, but it does bound it to [interruptedWait]: a stalled console (a paused terminal, or
     * another node holding the console lock) must not hold the worker forever. Lines still queued
     * after that are printed by the drain thread when the console frees up.
     */
    private fun awaitDrain() {
        if (!Thread.interrupted()) {
            try {
                drain.join()
                return
            } catch (_: InterruptedException) {
                // Interrupted while waiting: fall through to the bounded wait.
            }
        }
        val deadline = System.nanoTime() + interruptedWait.toNanos()
        while (drain.isAlive && System.nanoTime() < deadline) {
            try {
                drain.join(Duration.ofNanos(maxOf(1L, deadline - System.nanoTime())))
            } catch (_: InterruptedException) {
                // Keep waiting until the deadline; the interrupt is restored below.
            }
        }
        if (drain.isAlive) log.warn { "Stopped waiting for remote output to print after $interruptedWait" }
        Thread.currentThread().interrupt()
    }

    private fun deliver() {
        while (true) {
            when (val item = queue.take()) {
                Item.End -> return
                is Item.Line -> handOver(item)
            }
        }
    }

    private fun handOver(item: Item.Line) {
        try {
            sink.line(item.stream, item.text)
        } catch (e: RuntimeException) {
            log.warn(e) { "Could not print a line of remote output" }
        }
    }

    /** One queued line, or the end of the command's output. */
    private sealed interface Item {
        data class Line(
            val stream: NodeStream,
            val text: String,
        ) : Item

        data object End : Item
    }

    private companion object {
        private val log = KotlinLogging.logger {}
    }
}
