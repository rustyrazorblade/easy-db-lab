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

    /** Keys in the order they were opened. */
    val opened = mutableListOf<String>()

    /** Runs before the object [key] is opened. */
    var beforeOpen: (key: String) -> Unit = {}

    fun put(
        key: String,
        content: String,
        at: Instant = Instant.parse("2026-10-15T10:00:00Z"),
    ) {
        objects[key] = content.toByteArray() to at
    }

    override val bucket: String get() = "acct"

    override fun list(prefix: String): List<StoreObject> =
        objects.filterKeys { it.startsWith(prefix) }.map { (key, value) -> StoreObject(key, value.first.size.toLong(), value.second) }

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
}
