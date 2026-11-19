package com.rustyrazorblade.easydblab

import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Runs [block] with standard output redirected, and returns what it printed. For commands whose
 * result is plain `println` text rather than an event.
 */
fun captureStdout(block: () -> Unit): String {
    val original = System.out
    val captured = ByteArrayOutputStream()
    System.setOut(PrintStream(captured, true, Charsets.UTF_8))
    try {
        block()
    } finally {
        System.setOut(original)
    }
    return captured.toString(Charsets.UTF_8)
}
