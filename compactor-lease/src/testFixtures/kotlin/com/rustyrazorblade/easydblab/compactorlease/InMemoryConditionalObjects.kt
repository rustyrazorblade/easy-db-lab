package com.rustyrazorblade.easydblab.compactorlease

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * [ConditionalObjects] in memory, for tests of the lease and of anything built on the root
 * module's in-memory object store. Each write gets a version from one counter, so no two writes
 * share a version, as no two S3 writes of different content share an ETag.
 *
 * Each operation is atomic on its key ([ConcurrentHashMap.compute]), so racing writers behave as
 * they do against S3.
 */
class InMemoryConditionalObjects : ConditionalObjects {
    private val objects = ConcurrentHashMap<String, VersionedObject>()
    private val versions = AtomicLong()

    private fun nextVersion(): ObjectVersion = ObjectVersion("v${versions.incrementAndGet()}")

    override fun read(key: String): VersionedObject? = objects[key]

    override fun createIfAbsent(
        key: String,
        content: String,
    ): CreateResult {
        var result: CreateResult = CreateResult.AlreadyExists
        objects.computeIfAbsent(key) {
            VersionedObject(content, nextVersion()).also { result = CreateResult.Created(it.version) }
        }
        return result
    }

    override fun replaceIfVersionMatches(
        key: String,
        content: String,
        expected: ObjectVersion,
    ): ReplaceResult {
        var result: ReplaceResult = ReplaceResult.VersionMismatch
        objects.computeIfPresent(key) { _, current ->
            when (current.version) {
                expected -> VersionedObject(content, nextVersion()).also { result = ReplaceResult.Replaced(it.version) }
                else -> current
            }
        }
        return result
    }

    /**
     * Writes [content] at [key] whatever is there, as a plain PUT does, and returns the new version.
     * The lease code never calls it; an in-memory object store's plain writes do.
     */
    fun put(
        key: String,
        content: String,
    ): ObjectVersion = nextVersion().also { objects[key] = VersionedObject(content, it) }

    /** Removes the object at [key], as an operator might by hand; the lease code never deletes. */
    fun remove(key: String) {
        objects.remove(key)
    }

    /** Every key that holds an object. */
    fun keys(): Set<String> = objects.keys.toSet()
}
