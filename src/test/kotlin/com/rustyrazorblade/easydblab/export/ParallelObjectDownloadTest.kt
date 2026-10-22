package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * A staged object is downloaded as parallel ranged reads written at their offsets into a partial
 * file beside the target, which is renamed into place only once every part is there. A store copy
 * one stream at a time took most of an hour for one 9 GB block. Every byte must land where it was,
 * a failed download must leave neither a target nor a partial file, and a read that breaks part way
 * is read again.
 */
class ParallelObjectDownloadTest {
    @TempDir
    lateinit var dir: File

    private val store = FakeExportStore()
    private val noWait = RetryUtil.createRangedReadRetryConfig(Duration.ZERO)

    private fun put(
        key: String,
        size: Int,
    ): ByteArray = Random(size).nextBytes(size).also { store.objects[key] = it to java.time.Instant.EPOCH }

    private fun download(
        keys: List<String>,
        parallelism: Int = 4,
        partBytes: Long = 10,
    ): List<File> {
        val listing = store.list("")
        val jobs = keys.map { key -> ObjectDownload(listing.single { it.key == key }, File(dir, "out/$key")) }
        ParallelObjectDownload(store, parallelism, partBytes, noWait).download(jobs)
        return jobs.map { it.target }
    }

    @Test
    fun `objects of every size against the part are written byte for byte, through reads of at most one part each`() {
        val sizes = listOf(0, 1, 9, 10, 11, 30, 31, 257)
        val content = sizes.associate { "b/$it" to put("b/$it", it) }

        val files = download(content.keys.toList())

        files.zip(content.values).forEach { (file, bytes) -> assertThat(file.readBytes()).isEqualTo(bytes) }
        assertThat(store.openedRanges).allMatch { (_, range) -> range.last - range.first + 1 <= 10 }
        assertThat(store.openedRanges.filter { it.first == "b/257" }).hasSize(26)
        assertThat(dir.walkTopDown().filter { it.name.endsWith(Constants.Export.Query.STAGE_PARTIAL_SUFFIX) }.toList()).isEmpty()
    }

    @Test
    fun `no more parts are read at once than the parallelism allows`() {
        put("b/big", 400)
        val reading = AtomicInteger()
        val most = AtomicInteger()
        store.rangeStream = { _, range, bytes ->
            most.accumulateAndGet(reading.incrementAndGet(), ::maxOf)
            Thread.sleep(2)
            reading.decrementAndGet()
            bytes.copyOfRange(range.first.toInt(), range.last.toInt() + 1).inputStream()
        }

        download(listOf("b/big"), parallelism = 3)

        assertThat(most.get()).isBetween(1, 3)
    }

    @Test
    fun `a read that breaks part way is read again, and the file is still byte for byte`() {
        val bytes = put("b/x", 95)
        val broken = mutableSetOf<LongRange>()
        store.rangeStream = { _, range, all ->
            val part = all.copyOfRange(range.first.toInt(), range.last.toInt() + 1).inputStream()
            if (synchronized(broken) { broken.add(range) }) BreaksAfter(part, 3) else part
        }

        val file = download(listOf("b/x")).single()

        assertThat(file.readBytes()).isEqualTo(bytes)
        assertThat(store.openedRanges.count { it.first == "b/x" }).isEqualTo(20)
    }

    @Test
    fun `an object that is gone fails naming it, and leaves no target and no partial file`() {
        put("b/a", 50)
        put("b/gone", 50)
        val listing = store.list("")
        store.objects.remove("b/gone")

        assertThatThrownBy {
            ParallelObjectDownload(store, 2, 10, noWait).download(listing.map { ObjectDownload(it, File(dir, "out/${it.key}")) })
        }.isInstanceOf(ObjectGoneException::class.java).hasMessageContaining("b/gone")

        // Gone is not a broken read: no part of it is read twice.
        val goneReads = store.openedRanges.filter { it.first == "b/gone" }.map { it.second }
        assertThat(goneReads).doesNotHaveDuplicates()
        assertThat(File(dir, "out/b/gone")).doesNotExist()
        assertThat(dir.walkTopDown().filter { it.name.endsWith(Constants.Export.Query.STAGE_PARTIAL_SUFFIX) }.toList()).isEmpty()
    }

    @Test
    fun `a range that answers fewer bytes than it asked for fails rather than leave a short file`() {
        put("b/short", 25)
        store.rangeStream = { _, range, all -> all.copyOfRange(range.first.toInt(), range.last.toInt()).inputStream() }

        assertThatThrownBy { download(listOf("b/short")) }.hasMessageContaining("b/short")

        assertThat(File(dir, "out/b/short")).doesNotExist()
    }

    /** A stream whose connection drops after [bytes] bytes. */
    private class BreaksAfter(
        source: InputStream,
        private var bytes: Int,
    ) : FilterInputStream(source) {
        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (bytes <= 0) throw IOException("Connection reset")
            val read = super.read(b, off, minOf(len, bytes))
            if (read > 0) bytes -= read
            return read
        }
    }
}
