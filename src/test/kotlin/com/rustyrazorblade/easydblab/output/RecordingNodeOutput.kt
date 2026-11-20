package com.rustyrazorblade.easydblab.output

import java.util.Collections

/**
 * The real [ConsoleNodeOutput] with its console writes captured instead of printed.
 *
 * Tests read what a node line would have looked like on the console from [stdout] and [stderr],
 * so the tag, quiet mode and the tail are the production code's, not a fake's.
 */
class RecordingNodeOutput private constructor(
    val stdout: MutableList<String>,
    val stderr: MutableList<String>,
) : NodeOutput by ConsoleNodeOutput(stdout = { stdout.add(it) }, stderr = { stderr.add(it) }) {
    constructor() : this(
        Collections.synchronizedList(mutableListOf()),
        Collections.synchronizedList(mutableListOf()),
    )

    /** Every captured console line, stdout and stderr, in no particular cross-stream order. */
    val printed: List<String> get() = stdout + stderr
}
