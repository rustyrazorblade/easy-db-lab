package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One object to download, and where it goes.
 *
 * @property source the object, as the listing showed it (its size decides the ranges).
 * @property target the file it is written to.
 */
data class ObjectDownload(
    val source: StoreObject,
    val target: File,
)

/** A ranged read answered fewer bytes than it asked for: the object is not the one the listing showed. */
class ShortReadException(
    key: String,
    range: LongRange,
    read: Long,
) : IllegalStateException("$key: the range ${range.first}-${range.last} answered $read of ${range.last - range.first + 1} bytes")

/**
 * Downloads objects as parallel ranged reads ([ExportStore.openRange]). One stream read a compacted
 * Mimir block at 1-3 MB/s, so a 9 GB block took most of an hour; parts of every object of a call
 * are read [parallelism] at a time instead.
 *
 * Each object's parts ([ByteRanges], at most [partBytes] each) are written at their offsets into
 * `<target>.partial` ([Constants.Export.Query.STAGE_PARTIAL_SUFFIX]), and the file is renamed onto
 * the target, atomically, only once every part is there and its size is the listing's. So a target
 * that exists is always complete. A part whose read breaks is read again from its start ([retry]);
 * the writes are positional, so a repeated part writes the same bytes in the same place. Any other
 * failure (an object gone, a short read) stops the download: the parts still running finish their
 * current buffer, every partial file is removed, and the first failure is thrown.
 *
 * @property store the account bucket.
 * @property parallelism the most ranged reads at once.
 * @property partBytes the bytes of one ranged read.
 * @property retry how one ranged read is tried again.
 */
class ParallelObjectDownload(
    private val store: ExportStore,
    private val parallelism: Int,
    private val partBytes: Long,
    retry: RetryConfig = RetryUtil.createRangedReadRetryConfig(),
) {
    init {
        require(parallelism > 0) { "parallelism must be positive, was $parallelism" }
    }

    /** [retry], except that a gone object, an [java.io.IOException] too, is never read again. */
    private val retry: RetryConfig =
        RetryConfig
            .from<Any>(retry)
            .retryOnException { it !is ObjectGoneException && retry.exceptionPredicate.test(it) }
            .build()

    private companion object {
        const val ABORT_WAIT_MINUTES = 5L
        val THREADS = AtomicInteger()
    }

    /** Downloads every object of [objects], calling [partDone] with each part's object and bytes as it lands. */
    fun download(
        objects: List<ObjectDownload>,
        partDone: (ObjectDownload, Long) -> Unit = { _, _ -> },
    ) {
        val failed = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(parallelism, daemonThreads())
        try {
            val parts =
                objects.flatMap { job ->
                    val partial = partialOf(job.target).apply { parentFile.mkdirs() }
                    Files.deleteIfExists(partial.toPath())
                    val ranges = ByteRanges.split(job.source.size, partBytes)
                    if (ranges.isEmpty()) {
                        partial.createNewFile()
                        complete(job, partial)
                    }
                    val left = AtomicInteger(ranges.size)
                    ranges.map { range ->
                        pool.submit {
                            if (!failed.get()) {
                                read(job, partial, range, failed)
                                partDone(job, range.last - range.first + 1)
                                if (left.decrementAndGet() == 0) complete(job, partial)
                            }
                        }
                    }
                }
            await(parts, failed)
        } catch (e: Exception) {
            failed.set(true)
            pool.shutdown()
            pool.awaitTermination(ABORT_WAIT_MINUTES, TimeUnit.MINUTES)
            objects.forEach { Files.deleteIfExists(partialOf(it.target).toPath()) }
            throw e
        } finally {
            pool.shutdownNow()
        }
    }

    /** Waits for every part; throws the first part's own failure. */
    private fun await(
        parts: List<Future<*>>,
        failed: AtomicBoolean,
    ) {
        parts.forEach { part ->
            try {
                part.get()
            } catch (e: ExecutionException) {
                failed.set(true)
                throw e.cause ?: e
            }
        }
    }

    /** Reads [range] of [job]'s object into [partial] at its offset, again from its start when the read breaks. */
    private fun read(
        job: ObjectDownload,
        partial: File,
        range: LongRange,
        failed: AtomicBoolean,
    ) {
        val key = job.source.key
        Retry
            .decorateCheckedRunnable(Retry.of("ranged read $key", retry)) {
                val input = store.openRange(key, range) ?: throw ObjectGoneException(key)
                var position = range.first
                input.use { source ->
                    FileChannel.open(partial.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE).use { channel ->
                        val buffer = ByteArray(Constants.Export.COPY_BUFFER_BYTES)
                        while (position <= range.last && !failed.get()) {
                            val read = source.read(buffer, 0, minOf(buffer.size.toLong(), range.last + 1 - position).toInt())
                            if (read < 0) break
                            val bytes = ByteBuffer.wrap(buffer, 0, read)
                            while (bytes.hasRemaining()) position += channel.write(bytes, position)
                        }
                    }
                }
                if (!failed.get() && position != range.last + 1) throw ShortReadException(key, range, position - range.first)
            }.run()
    }

    /** Renames [partial] onto [job]'s target once it holds the whole object. */
    private fun complete(
        job: ObjectDownload,
        partial: File,
    ) {
        check(partial.length() == job.source.size) {
            "${job.source.key}: ${partial.length()} bytes written of ${job.source.size}"
        }
        Files.move(partial.toPath(), job.target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun partialOf(target: File): File = File(target.path + Constants.Export.Query.STAGE_PARTIAL_SUFFIX)

    private fun daemonThreads(): ThreadFactory =
        ThreadFactory { task ->
            Thread(task, "edl-export-download-${THREADS.incrementAndGet()}").apply { isDaemon = true }
        }
}
