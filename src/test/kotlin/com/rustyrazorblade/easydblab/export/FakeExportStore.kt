package com.rustyrazorblade.easydblab.export

import java.io.InputStream
import java.time.Instant

/**
 * An account bucket in memory, read the way [ExportStore] reads S3. [beforeOpen] runs before each
 * object is opened, so a test can change the store the way a running account compactor would.
 */
class FakeExportStore : ExportStore {
    /** Every object, by key, with its bytes and its last-modified time. */
    val objects = sortedMapOf<String, Pair<ByteArray, Instant>>()

    /** Keys in the order they were opened, whole or by range (once per ranged read). */
    val opened: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** Runs before the object [key] is opened. */
    var beforeOpen: (key: String) -> Unit = {}

    /** Runs before [prefix] is listed; a test throws from it to fail a listing as S3 can. */
    var beforeList: (prefix: String) -> Unit = {}

    fun put(
        key: String,
        content: String,
        at: Instant = Instant.parse("2026-10-15T10:00:00Z"),
    ) {
        objects[key] = content.toByteArray() to at
    }

    override val bucket: String get() = "acct"

    override fun list(prefix: String): List<StoreObject> {
        beforeList(prefix)
        return objects.filterKeys { it.startsWith(prefix) }.map { (key, value) ->
            StoreObject(key, value.first.size.toLong(), value.second)
        }
    }

    override fun listDirectories(prefix: String): List<String> =
        objects.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { '/' in it }
            .map { prefix + it.substringBefore('/') + "/" }
            .distinct()

    override fun open(key: String): InputStream? {
        beforeOpen(key)
        opened += key
        return objects[key]?.first?.inputStream()
    }

    /** Every ranged read, in the order it was opened. */
    val openedRanges: MutableList<Pair<String, LongRange>> = java.util.Collections.synchronizedList(mutableListOf())

    /** Answers a ranged read of an object that is there, from its bytes; a test replaces it to fail or slow one. */
    var rangeStream: (key: String, range: LongRange, bytes: ByteArray) -> InputStream = { _, range, bytes ->
        bytes.copyOfRange(range.first.toInt(), minOf(range.last + 1, bytes.size.toLong()).toInt()).inputStream()
    }

    override fun openRange(
        key: String,
        range: LongRange,
    ): InputStream? {
        beforeOpen(key)
        openedRanges += key to range
        opened += key
        return objects[key]?.first?.let { rangeStream(key, range, it) }
    }
}
