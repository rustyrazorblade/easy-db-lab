package com.rustyrazorblade.easydblab.compactorlease

/**
 * The conditional writes of an object store: the only primitive the compactor lease is built on.
 *
 * Every write names what it expects to find, so of two writers that race, one wins and the other
 * learns it lost. No operation is retried: a retry of a write that landed would see its own write
 * and report a loss, so the caller would give up a lease it holds. Any failure other than a lost
 * race throws.
 *
 * Keys are object keys within one bucket, chosen by the adapter.
 */
interface ConditionalObjects {
    /** The object at [key] with its version, or `null` when no object exists there. */
    fun read(key: String): VersionedObject?

    /** Writes [content] at [key] only when no object exists there. */
    fun createIfAbsent(
        key: String,
        content: String,
    ): CreateResult

    /**
     * Writes [content] at [key] only when the object there has version [expected]. A missing object
     * is a [ReplaceResult.VersionMismatch].
     */
    fun replaceIfVersionMatches(
        key: String,
        content: String,
        expected: ObjectVersion,
    ): ReplaceResult
}

/**
 * An object store's version token for one write of one object: the ETag on S3. Two writes of one
 * key never share a version when their content differs.
 */
@JvmInline
value class ObjectVersion(
    val token: String,
) {
    init {
        require(token.isNotBlank()) { "An object version must not be blank" }
    }
}

/** An object's content and the version it was read at. */
data class VersionedObject(
    val content: String,
    val version: ObjectVersion,
)

/** The outcome of [ConditionalObjects.createIfAbsent]. */
sealed interface CreateResult {
    /** The object did not exist and now holds the content, at [version]. */
    data class Created(
        val version: ObjectVersion,
    ) : CreateResult

    /** An object already existed at the key; nothing was written. */
    data object AlreadyExists : CreateResult
}

/** The outcome of [ConditionalObjects.replaceIfVersionMatches]. */
sealed interface ReplaceResult {
    /** The object was at the expected version and now holds the content, at [version]. */
    data class Replaced(
        val version: ObjectVersion,
    ) : ReplaceResult

    /** The object was at another version, or missing; nothing was written. */
    data object VersionMismatch : ReplaceResult
}
