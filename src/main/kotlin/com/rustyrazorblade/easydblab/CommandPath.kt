package com.rustyrazorblade.easydblab

import picocli.CommandLine.ParseResult

/**
 * The command as the user typed it, without the root (`cassandra use` for `easy-db-lab cassandra use`):
 * the names of this parse result's command and every parent but the root, outermost first.
 */
fun ParseResult.commandPath(): String =
    generateSequence(commandSpec()) { it.parent() }
        .filter { it.parent() != null }
        .map { it.name() }
        .toList()
        .asReversed()
        .joinToString(" ")
