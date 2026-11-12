package com.rustyrazorblade.easydblab.compactorlease

import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * [ConditionalObjects] in memory, for tests of the lease. Each write gets a version from one
 * counter, so no two writes share a version, as no two S3 writes of different content share an
 * ETag.
 *
 * Each operation is atomic on its key ([ConcurrentHashMap.compute]), so racing writers behave as
 * they do against S3.
 *
 * @property versionsFromContent when set, a version is the MD5 of the content, as a single-part S3
 *   ETag is, so writing the same content again gets the same version back.
 */
class InMemoryConditionalObjects(
    private val versionsFromContent: Boolean = false,
) : ConditionalObjects {
    private val objects = ConcurrentHashMap<String, VersionedObject>()
    private val versions = AtomicLong()

    /** What the next replace that lands answers instead of [ReplaceResult.Replaced]; see [loseNextReplaceAnswer]. */
    @Volatile
    private var lostAnswer: (() -> ReplaceResult)? = null

    private fun nextVersion(content: String): ObjectVersion =
        when {
            versionsFromContent ->
                ObjectVersion(HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content.toByteArray())))
            else -> ObjectVersion("v${versions.incrementAndGet()}")
        }

    /**
     * Makes the next replace that lands answer with [answer] instead: it throws, as a PUT that S3
     * committed but whose response timed out does, or returns a [ReplaceResult.VersionMismatch], as
     * a 409 for a write that committed does. The write is in the store either way.
     */
    fun loseNextReplaceAnswer(answer: () -> ReplaceResult) {
        lostAnswer = answer
    }

    override fun read(key: String): VersionedObject? = objects[key]

    override fun createIfAbsent(
        key: String,
        content: String,
    ): CreateResult {
        var result: CreateResult = CreateResult.AlreadyExists
        objects.computeIfAbsent(key) {
            VersionedObject(content, nextVersion(content)).also { result = CreateResult.Created(it.version) }
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
                expected -> VersionedObject(content, nextVersion(content)).also { result = ReplaceResult.Replaced(it.version) }
                else -> current
            }
        }
        val answer = lostAnswer
        if (result is ReplaceResult.Replaced && answer != null) {
            lostAnswer = null
            return answer()
        }
        return result
    }

    /**
     * Writes [content] at [key] whatever is there, as a plain PUT does, and returns the new version.
     * The lease code never calls it; a test does, to put a body there by hand.
     */
    fun put(
        key: String,
        content: String,
    ): ObjectVersion = nextVersion(content).also { objects[key] = VersionedObject(content, it) }

    /** Removes the object at [key], as an operator might by hand; the lease code never deletes. */
    fun remove(key: String) {
        objects.remove(key)
    }
}
