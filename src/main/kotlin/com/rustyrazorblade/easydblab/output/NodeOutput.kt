package com.rustyrazorblade.easydblab.output

/** Which of a remote command's two output streams a line came from. */
enum class NodeStream {
    Stdout,
    Stderr,
}

/**
 * The read side of [NodeOutput]: a node's recent output, kept so a failure report can show it.
 *
 * Code that only reports a failure depends on this narrow view, so it never writes output itself.
 */
interface NodeOutputTail {
    /** The last lines [NodeOutput] recorded for [node]'s current instance, oldest first, untagged. */
    fun tail(node: String): List<String>

    /**
     * Starts a new tail for [node], belonging to [instance], so the next tail holds only output
     * that follows.  A later line of another instance is printed but not kept (see [NodeOutput.line]).
     * An empty [instance] keeps every line, whatever instance it names.
     */
    fun reset(
        node: String,
        instance: String = "",
    )
}

/**
 * Where the raw output of commands run on cluster nodes goes.
 *
 * Raw output is not an event: it is printed as plain text, one whole line at a time, tagged with
 * the node it came from, so the lines of many nodes running at once can be told apart.  The port
 * names a node by its alias and an instance by an opaque id, and is free of any transport: the SSH
 * adapter writes into it today, and any other way of running a command on a node writes into the
 * same port.  Every line is redacted before it is printed or kept.
 */
interface NodeOutput : NodeOutputTail {
    /**
     * One complete line of [node]'s output on [stream], from [instance] when it is known.  A line
     * whose [instance] is not the one [node]'s tail belongs to (an attempt left behind on an
     * instance that ended) is printed, but not kept, and not printed while quiet.
     */
    fun line(
        node: String,
        stream: NodeStream,
        text: String,
        instance: String = "",
    )

    /** A line about [node] from the tool itself, such as an upload; printed, never kept in the tail. */
    fun notice(
        node: String,
        text: String,
    )

    /**
     * A line from the tool that marks where a run of [node]'s output starts (a retry, a new
     * attempt); printed and kept in the tail, so a failure report shows which run a line is from.
     */
    fun mark(
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
     * Runs [block] with node, mark and notice lines hidden from the console.  Tails are still
     * kept, so a failure report still shows them.  Quiet lasts while any [quietly] block runs, on
     * any thread, and ends when the last one returns or throws.
     */
    fun <T> quietly(block: () -> T): T
}
