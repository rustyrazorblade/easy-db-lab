package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.SharedMinio
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import kotlin.random.Random

/**
 * Staged objects are read from S3 (MinIO here) as parallel ranged GETs: an object several parts long,
 * one one byte past a part boundary, one exactly one part and an empty one must land byte for byte,
 * through `Range: bytes=a-b` reads of at most one part each.
 */
class ParallelObjectDownloadIntegrationTest {
    @TempDir
    lateinit var dir: File

    private val bucket = "edl-parallel-download"
    private val part = 1024L * 1024

    /** [S3ExportStore], counting the ranges it reads. */
    private class CountingStore(
        private val inner: ExportStore,
    ) : ExportStore by inner {
        val ranges: MutableList<Pair<String, LongRange>> = Collections.synchronizedList(mutableListOf())

        override fun openRange(
            key: String,
            range: LongRange,
        ): InputStream? {
            ranges += key to range
            return inner.openRange(key, range)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `objects downloaded by parallel ranged GETs are byte for byte the stored ones`() {
        val s3 = SharedMinio.s3Client()
        SharedMinio.createBucketIfMissing(s3, bucket)
        val sizes = mapOf("multi" to 5 * part + 12_345, "boundary" to 2 * part + 1, "one" to part, "empty" to 0L)
        val content =
            sizes.mapValues { (name, size) ->
                Random(name.hashCode()).nextBytes(size.toInt()).also { bytes ->
                    s3.putObject(
                        PutObjectRequest
                            .builder()
                            .bucket(bucket)
                            .key("mimir/t/b/$name")
                            .build(),
                        RequestBody.fromBytes(bytes),
                    )
                }
            }
        val store = CountingStore(S3ExportStore(s3, bucket))
        val jobs = store.list("mimir/t/b/").map { ObjectDownload(it, File(dir, it.key)) }

        ParallelObjectDownload(store, parallelism = 4, partBytes = part).download(jobs)

        content.forEach { (name, bytes) ->
            assertThat(sha256(File(dir, "mimir/t/b/$name").readBytes())).describedAs(name).isEqualTo(sha256(bytes))
        }
        assertThat(store.ranges.filter { it.first.endsWith("multi") }).hasSize(6)
        assertThat(store.ranges.filter { it.first.endsWith("boundary") }.map { it.second }).contains(2 * part..2 * part)
        assertThat(store.ranges).allMatch { (_, range) -> range.last - range.first + 1 <= part }
        assertThat(store.ranges.filter { it.first.endsWith("empty") }).isEmpty()
    }
}
