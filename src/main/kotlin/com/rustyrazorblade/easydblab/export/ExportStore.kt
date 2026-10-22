package com.rustyrazorblade.easydblab.export

import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.io.InputStream
import java.time.Instant

/**
 * One object in the account bucket, as a listing shows it.
 *
 * @property key the object's key.
 * @property size its size in bytes.
 * @property lastModified when it was written.
 */
data class StoreObject(
    val key: String,
    val size: Long,
    val lastModified: Instant,
)

/**
 * The account bucket as an export reads it. It can only list and read: an export writes nothing to
 * S3, deletes nothing and uploads nothing, and this interface has no way to.
 */
interface ExportStore : AutoCloseable {
    /** The bucket, for messages. */
    val bucket: String

    /** Releases the store's client; nothing to release by default. */
    override fun close() = Unit

    /** Every object whose key starts with [prefix], in key order. */
    fun list(prefix: String): List<StoreObject>

    /** The "directories" right under [prefix] (each ends in `/`), as a listing with `/` as the delimiter shows them. */
    fun listDirectories(prefix: String): List<String>

    /** The content of [key], or null when it is gone; the caller closes the stream. */
    fun open(key: String): InputStream?

    /**
     * The bytes [range] (inclusive, as HTTP `Range: bytes=a-b`) of [key], or null when it is gone; the
     * caller closes the stream.
     */
    fun openRange(
        key: String,
        range: LongRange,
    ): InputStream?
}

/**
 * [ExportStore] over S3, with a client for the bucket's own region.
 *
 * @property s3 the client.
 */
class S3ExportStore(
    private val s3: S3Client,
    override val bucket: String,
) : ExportStore {
    override fun list(prefix: String): List<StoreObject> =
        s3
            .listObjectsV2Paginator(
                ListObjectsV2Request
                    .builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .build(),
            ).contents()
            .map { StoreObject(it.key(), it.size(), it.lastModified()) }

    override fun listDirectories(prefix: String): List<String> =
        s3
            .listObjectsV2Paginator(
                ListObjectsV2Request
                    .builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .delimiter("/")
                    .build(),
            ).commonPrefixes()
            .map { it.prefix() }

    override fun close() = s3.close()

    override fun open(key: String): InputStream? = get(GetObjectRequest.builder().bucket(bucket).key(key))

    override fun openRange(
        key: String,
        range: LongRange,
    ): InputStream? =
        get(
            GetObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .range("bytes=${range.first}-${range.last}"),
        )

    private fun get(request: GetObjectRequest.Builder): InputStream? =
        try {
            s3.getObject(request.build())
        } catch (_: NoSuchKeyException) {
            null
        }
}
