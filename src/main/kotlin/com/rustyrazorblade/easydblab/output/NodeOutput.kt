package com.rustyrazorblade.easydblab.output

/** Which of a remote command's two output streams a line came from. */
enum class NodeStream {
    Stdout,
    Stderr,
}

/**
 * The read side of [NodeOutput]: a node's recent output, kept so a failure report can show it.
 *
 * The bring-up depends on this narrow view only, so it never writes output itself.
 */
interface NodeOutputTail {
    /** The last lines [NodeOutput] recorded for [node], oldest first, untagged. */
    fun tail(node: String): List<String>

    /** Forgets what was recorded for [node], so the next tail holds only output that follows. */
    fun reset(node: String)
}

/**
 * Where the raw output of commands run on cluster nodes goes.
 *
 * Raw output is not an event: it is printed as plain text, one whole line at a time, tagged with
 * the node it came from, so the lines of many nodes running at once can be told apart.  The port
 * names a node by its alias and is free of any transport: the SSH adapter writes into it today,
 * and any other way of running a command on a node writes into the same port.
 */
interface NodeOutput : NodeOutputTail {
    /** One complete line of [node]'s output on [stream]. */
    fun line(
        node: String,
        stream: NodeStream,
        text: String,
    )

    /** A line about [node] from the tool itself, such as an upload. */
    fun notice(
        node: String,
        text: String,
    )

    /**
     * Prints [text] split into lines, each tagged with [source], and keeps none of them in a tail.
     * For output whose source is short-lived and unique (a pod), where a kept buffer would never
     * be read and never freed.
     */
    fun passthrough(
        source: String,
        text: String,
    )

    /**
     * Runs [block] with node and notice lines hidden from the console.  Tails are still kept, so a
     * failure report still shows them.  The previous mode is restored when [block] returns or throws.
     */
    fun <T> quietly(block: () -> T): T
}
