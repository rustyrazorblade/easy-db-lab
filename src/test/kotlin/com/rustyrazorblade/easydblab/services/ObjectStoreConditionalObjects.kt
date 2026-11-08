package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.compactorlease.ConditionalObjects
import com.rustyrazorblade.easydblab.compactorlease.CreateResult
import com.rustyrazorblade.easydblab.compactorlease.ObjectVersion
import com.rustyrazorblade.easydblab.compactorlease.ReplaceResult
import com.rustyrazorblade.easydblab.compactorlease.VersionedObject
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path

/**
 * An [ObjectStore]'s conditional writes seen as [ConditionalObjects], with every key in [bucket].
 * It lets the one conditional-write contract of `:compactor-lease` run against each `ObjectStore`
 * adapter, so the in-memory store and the S3 store are held to the same behavior.
 */
class ObjectStoreConditionalObjects(
    private val store: ObjectStore,
    private val bucket: String,
) : ConditionalObjects {
    private fun path(key: String) = ClusterS3Path.fromKey(bucket, key)

    override fun read(key: String): VersionedObject? = store.readVersioned(path(key))

    override fun createIfAbsent(
        key: String,
        content: String,
    ): CreateResult = store.createIfAbsent(path(key), content)

    override fun replaceIfVersionMatches(
        key: String,
        content: String,
        expected: ObjectVersion,
    ): ReplaceResult = store.replaceIfVersionMatches(path(key), content, expected)
}
