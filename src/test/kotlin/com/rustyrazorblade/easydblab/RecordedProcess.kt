package com.rustyrazorblade.easydblab

import java.io.File
import java.time.Duration

/**
 * The live process a test's stand-in recorded in [record] (a file holding its PID), or null when
 * the record is missing or that PID no longer belongs to it.
 *
 * Unit tests run in parallel forks, so once the recorded process exits its PID can be given to
 * another test's process. A cleanup that signals the bare PID would kill that process. The PID
 * counts as the recorded process only when the process holding it started before the record was
 * written (it wrote the record itself) and its command line contains [command].
 */
fun recordedProcess(
    record: File,
    command: String,
): ProcessHandle? {
    val pid =
        record
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.toLongOrNull() ?: return null
    val writtenAt = record.lastModified()
    return ProcessHandle
        .of(pid)
        .filter { handle ->
            val info = handle.info()
            val started = info.startInstant().map { it.toEpochMilli() <= writtenAt + START_SLACK.toMillis() }.orElse(false)
            started && info.commandLine().map { it.contains(command) }.orElse(false)
        }.orElse(null)
}

/** Room for the coarser clock of a process start time than of a file's modification time. */
private val START_SLACK: Duration = Duration.ofSeconds(1)
