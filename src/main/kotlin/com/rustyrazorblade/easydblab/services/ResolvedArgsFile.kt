package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A kit's recorded install args, `<kitDir>/kit-resolved-args.env`: one `KEY=VALUE` per line, written
 * by `kit install` and read by every later command so it runs with the values the user installed.
 *
 * [rewrite] is the one way a kit command changes a recorded arg after install (`cassandra-pods
 * upgrade` records the version it applied). It keeps every other line, comments included, and
 * replaces the file by an atomic rename, so a reader never sees half of it.
 */
class ResolvedArgsFile(
    kitDir: File,
) {
    private val file = File(kitDir, Constants.Kit.RESOLVED_ARGS_FILE)

    /** The recorded args, or none when the kit has no file. */
    fun read(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return file
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val idx = line.indexOf('=')
                if (idx < 0) line to "" else line.substring(0, idx) to line.substring(idx + 1)
            }
    }

    /**
     * Sets each arg in [values], appending one the file does not hold yet, and keeps every other line.
     *
     * @throws IllegalArgumentException naming the arg when a value holds a line break, which would
     *   inject a second entry; the file is left unchanged
     */
    fun rewrite(values: Map<String, String>) {
        values.entries.firstOrNull { (_, v) -> '\n' in v || '\r' in v }?.let { (k, _) ->
            throw IllegalArgumentException("Recorded arg value for $k contains a line break")
        }
        val lines = if (file.isFile) file.readLines().filter { it.isNotEmpty() } else emptyList()
        val keyOf = { line: String -> line.substringBefore('=').takeUnless { line.startsWith("#") } }
        val kept = lines.map { line -> keyOf(line)?.let { key -> values[key]?.let { "$key=$it" } } ?: line }
        val present = lines.mapNotNull(keyOf).toSet()
        val appended = values.filterKeys { it !in present }.map { (k, v) -> "$k=$v" }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText((kept + appended).joinToString("\n") + "\n")
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
