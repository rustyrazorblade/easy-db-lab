package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.export.FakeExportStore
import com.rustyrazorblade.easydblab.export.MimirBucketIndexWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A query export's metrics (design section 7, spike S4): the tenant's blocks that overlap the
 * clusters' ranges and carry no deletion mark are staged from the store, rewritten to delete every
 * series of another cluster, the originals and the rewritten blocks with no series dropped, and the
 * bucket index written last from exactly the blocks left. Nothing in the store is written.
 */
class MetricsExportTest {
    @TempDir
    lateinit var work: File

    private val store = FakeExportStore()
    private val hour = 3_600_000L
    private val day0 = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()

    /** The series a rewritten block keeps, by source block. */
    private var keptSeries: Map<String, Int> = emptyMap()
    private val rewrites = mutableListOf<RewriteCall>()

    private data class RewriteCall(
        val tenantDir: File,
        val tmpDir: File,
        val blocks: List<String>,
        val clusters: List<String>,
    )

    /** Does on disk what thanos does: one new block per source, with the series it keeps. */
    private val rewrite =
        BlockRewrite { tenantDir, tmpDir, blocks, clusters ->
            rewrites += RewriteCall(tenantDir, tmpDir, blocks, clusters)
            tmpDir.mkdirs()
            blocks.forEach { source ->
                val id = "R$source"
                val series = keptSeries[source] ?: 0
                val stats = if (series == 0) "{}" else """{"numSeries":$series}"""
                File(tenantDir, "$id/chunks").mkdirs()
                File(tenantDir, "$id/chunks/000001").writeText("chunk")
                File(tenantDir, "$id/index").writeText("index")
                File(tenantDir, "$id/meta.json").writeText(
                    """{"ulid":"$id","minTime":1,"maxTime":2,"stats":$stats,"compaction":{"level":1},"thanos":{"labels":{},"source":"bucket.rewrite"}}""",
                )
            }
        }

    private fun block(
        id: String,
        min: Long,
        max: Long,
    ) {
        store.put("mimir/acme/$id/meta.json", """{"ulid":"$id","minTime":$min,"maxTime":$max}""")
        store.put("mimir/acme/$id/index", "index of $id")
        store.put("mimir/acme/$id/chunks/000001", "chunks of $id")
        store.put("mimir/acme/$id/no-compact-mark.json", "{}")
    }

    private fun index(
        blocks: List<Triple<String, Long, Long>>,
        marked: List<String> = emptyList(),
    ) {
        val json =
            """{"version":2,"blocks":[${blocks.joinToString(
                ",",
            ) { (id, min, max) -> """{"block_id":"$id","min_time":$min,"max_time":$max}""" }}],
            "block_deletion_marks":[${marked.joinToString(",") { """{"block_id":"$it"}""" }}],"updated_at":1}"""
        store.objects["mimir/acme/bucket-index.json.gz"] =
            ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(json.toByteArray()) } }.toByteArray() to
            Instant.EPOCH
    }

    private fun seed() {
        block("01OLD", day0, day0 + hour)
        block("01HIT", day0 + 10 * hour, day0 + 11 * hour)
        block("01MRK", day0 + 10 * hour, day0 + 11 * hour)
        block("01EMP", day0 + 12 * hour, day0 + 13 * hour)
        index(
            listOf(
                Triple("01OLD", day0, day0 + hour),
                Triple("01HIT", day0 + 10 * hour, day0 + 11 * hour),
                Triple("01MRK", day0 + 10 * hour, day0 + 11 * hour),
                Triple("01EMP", day0 + 12 * hour, day0 + 13 * hour),
            ),
            marked = listOf("01MRK"),
        )
    }

    private val ranges = listOf(TimeRange(Instant.ofEpochMilli(day0 + 9 * hour), Instant.ofEpochMilli(day0 + 14 * hour)))
    private val export = MetricsExport(store, rewrite, MimirBucketIndexWriter())

    @Test
    fun `only blocks that overlap a cluster's range and carry no deletion mark are staged, with no marker files`() {
        seed()
        keptSeries = mapOf("01HIT" to 3, "01EMP" to 1)

        export.export("acme", listOf("x-1", "y-2"), ranges, work)

        assertThat(rewrites.single().blocks).containsExactly("01EMP", "01HIT")
        assertThat(store.opened).noneMatch { it.contains("01OLD") || it.contains("01MRK") || it.endsWith("no-compact-mark.json") }
        assertThat(rewrites.single().clusters).containsExactly("x-1", "y-2")
    }

    @Test
    fun `the originals and the rewritten blocks with no series are dropped, then the index lists exactly the blocks left`() {
        seed()
        keptSeries = mapOf("01HIT" to 3)

        val count = export.export("acme", listOf("x-1"), ranges, work)

        val tenantDir = File(work, "mimir/acme")
        assertThat(tenantDir.list()).containsExactlyInAnyOrder("R01HIT", MimirBucketIndexWriter.FILE)
        val indexed =
            Json
                .parseToJsonElement(
                    GZIPInputStream(File(tenantDir, MimirBucketIndexWriter.FILE).inputStream()).readBytes().decodeToString(),
                ).jsonObject
                .getValue("blocks")
                .jsonArray
                .map {
                    it.jsonObject
                        .getValue("block_id")
                        .jsonPrimitive.content
                }
        assertThat(indexed).containsExactly("R01HIT")
        assertThat(count.items).isEqualTo(1)
        assertThat(count.bytes).isEqualTo(tenantDir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        assertThat(rewrites.single().tmpDir.exists()).isFalse()
    }

    @Test
    fun `thanos's scratch is outside the tenant directory, which is the objstore root`() {
        seed()
        keptSeries = mapOf("01HIT" to 3)

        export.export("acme", listOf("x-1"), ranges, work)

        val call = rewrites.single()
        assertThat(call.tenantDir).isEqualTo(File(work, "mimir/acme"))
        assertThat(call.tmpDir.toPath().startsWith(call.tenantDir.toPath())).isFalse()
    }

    @Test
    fun `no overlapping block exports nothing and runs no rewrite`() {
        seed()

        val count =
            export.export(
                "acme",
                listOf("x-1"),
                listOf(
                    TimeRange(
                        Instant.ofEpochMilli(day0 + 100 * hour),
                        Instant.ofEpochMilli(
                            day0 + 101 * hour,
                        ),
                    ),
                ),
                work,
            )

        assertThat(count).isEqualTo(SignalCount.ZERO)
        assertThat(rewrites).isEmpty()
        assertThat(File(work, "mimir/acme").exists()).isFalse()
    }

    @Test
    fun `a tenant's oldest block start bounds the clusters' range search`() {
        seed()

        assertThat(export.oldestBlock("acme")).isEqualTo(Instant.ofEpochMilli(day0))
    }

    @Test
    fun `a tenant with blocks and no bucket index fails naming the compactor`() {
        block("01HIT", day0, day0 + hour)

        assertThatThrownBy { export.export("acme", listOf("x-1"), ranges, work) }.hasMessageContaining("compactor")
    }

    /** A rewrite that fails, such as one whose thanos may still be writing, must leave the staged sources and write no index. */
    @Test
    fun `a failed rewrite keeps the staged source blocks and writes no bucket index`() {
        seed()
        val failing = BlockRewrite { _, _, _, _ -> error("thanos may still be running") }

        assertThatThrownBy { MetricsExport(store, failing, MimirBucketIndexWriter()).export("acme", listOf("x-1"), ranges, work) }
            .hasMessageContaining("thanos may still be running")

        assertThat(File(work, "mimir/acme/01HIT/meta.json")).isFile()
        assertThat(File(work, "mimir/acme/${MimirBucketIndexWriter.FILE}")).doesNotExist()
    }

    /** A 9 GB tenant staged for 20 minutes with nothing on screen. */
    @Test
    fun `staging reports the blocks and bytes staged of the tenant's total, and once at the end`() {
        seed()
        keptSeries = mapOf("01HIT" to 3)
        val staged =
            java.util.Collections.synchronizedList(
                mutableListOf<com.rustyrazorblade.easydblab.events.Event.Export.MetricsStaged>(),
            )
        val total = store.list("mimir/acme/").filter { it.key.contains("01HIT/") || it.key.contains("01EMP/") }
        val totalBytes = total.filterNot { it.key.endsWith("no-compact-mark.json") }.sumOf { it.size }
        val everyByte = ProgressPolicy(every = 1, interval = java.time.Duration.ofHours(1), clock = java.time.Clock.systemUTC())

        MetricsExport(store, rewrite, MimirBucketIndexWriter(), progress = everyByte, onProgress = { staged += it })
            .export("acme", listOf("x-1"), ranges, work)

        assertThat(staged).isNotEmpty().allMatch { it.tenant == "acme" && it.totalBlocks == 2 && it.totalBytes == totalBytes }
        assertThat(staged.map { it.bytes }).isSorted().doesNotHaveDuplicates()
        assertThat(staged.map { it.blocks }).isSorted()
        assertThat(staged.last()).isEqualTo(
            com.rustyrazorblade.easydblab.events.Event.Export
                .MetricsStaged("acme", 2, 2, totalBytes, totalBytes),
        )
        assertThat(staged.last().toDisplayString()).contains("acme", "2 of 2 blocks")
    }

    @Test
    fun `a tenant with no block to stage reports no progress`() {
        seed()
        val staged = mutableListOf<com.rustyrazorblade.easydblab.events.Event.Export.MetricsStaged>()
        val farAway = listOf(TimeRange(Instant.ofEpochMilli(day0 + 100 * hour), Instant.ofEpochMilli(day0 + 101 * hour)))

        MetricsExport(store, rewrite, MimirBucketIndexWriter(), onProgress = { staged += it }).export("acme", listOf("x-1"), farAway, work)

        assertThat(staged).isEmpty()
    }

    @Test
    fun `nothing in the store is written`() {
        seed()
        keptSeries = mapOf("01HIT" to 3)
        val before = store.objects.mapValues { it.value.first.toList() }

        export.export("acme", listOf("x-1"), ranges, work)

        assertThat(store.objects.mapValues { it.value.first.toList() }).isEqualTo(before)
    }
}
