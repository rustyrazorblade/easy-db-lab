package com.rustyrazorblade.easydblab.export

import java.io.InputStream
import java.time.Instant

/**
 * Puts entries into an export's zip and remembers what it put, so an index can be written from what
 * is in the zip rather than from what was meant to be copied.
 *
 * It keeps each entry's size and source time, and the bytes of every `meta.json`, which the Mimir and
 * Tempo index writers read. [directory] is a tenant directory of the zip as those writers see it.
 *
 * @property sink the zip.
 */
class ExportRecorder(
    private val sink: ExportZipSink,
) {
    private class Entry(
        val size: Long,
        val writtenAt: Instant,
        val meta: ByteArray?,
    )

    private val entries = LinkedHashMap<String, Entry>()

    /** The bytes put so far. */
    val bytes: Long get() = entries.values.sumOf { it.size }

    /** The number of entries put so far. */
    val count: Int get() = entries.size

    /**
     * Streams [input] to [path] (relative to the stack directory) and closes it.
     *
     * @param writtenAt when the source was written; Mimir's index records it for `meta.json`.
     */
    fun put(
        path: String,
        input: InputStream,
        writtenAt: Instant = Instant.EPOCH,
    ): Long {
        val meta = if (path.substringAfterLast('/') == META_FILE) input.use { it.readBytes() } else null
        val size = sink.put(path, meta?.inputStream() ?: input)
        entries[path] = Entry(size, writtenAt, meta)
        return size
    }

    /** Puts [bytes] at [path]. */
    fun put(
        path: String,
        bytes: ByteArray,
    ): Long = put(path, bytes.inputStream())

    /** The block directories under [prefix] in the zip (`data/mimir/<tenant>`, for example), with each block's `meta.json`. */
    fun directory(prefix: String): BlockDirectory =
        object : BlockDirectory {
            override val description: String get() = "$prefix in the export"

            override fun blocks(): Map<String, StagedBlock> =
                entries.keys
                    .filter { it.startsWith("$prefix/") && it.removePrefix("$prefix/").contains('/') }
                    .map { it.removePrefix("$prefix/").substringBefore('/') }
                    .distinct()
                    .associateWith { id ->
                        val meta = entries["$prefix/$id/$META_FILE"]
                        StagedBlock(id, meta?.meta, meta?.writtenAt ?: Instant.EPOCH)
                    }
        }

    private companion object {
        const val META_FILE = "meta.json"
    }
}
