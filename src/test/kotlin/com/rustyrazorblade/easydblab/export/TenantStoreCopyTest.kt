package com.rustyrazorblade.easydblab.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A tenant export copies the tenant's store consistently while the account compactor runs: Loki's
 * index table by table, redoing a table replaced mid-copy; Tempo's blocks by one listing, with the
 * zip's own tenant index; Mimir's blocks from one read of the bucket index, with the zip's own index
 * last; the immutable objects last. Everything lands under `data/` with its store key.
 */
class TenantStoreCopyTest {
    @TempDir
    lateinit var scratch: File

    private val store = FakeExportStore()
    private val clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC)
    private val copy = TenantStoreCopy(store, MimirBucketIndexWriter(clock), TempoTenantIndexWriter(clock))

    /** Every entry the copy put, in order, with its bytes. */
    private val entries = linkedMapOf<String, ByteArray>()
    private val sink =
        ExportZipSink { path, input ->
            input
                .use { it.readBytes() }
                .also { entries[path] = it }
                .size
                .toLong()
        }

    private fun run(tenant: String = "acme") = copy.copy(copy.plan(tenant), ExportRecorder(sink), scratch)

    private fun putGzip(
        key: String,
        json: String,
    ) {
        val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(json.toByteArray()) } }.toByteArray()
        store.objects[key] = gzipped to Instant.parse("2026-10-15T10:00:00Z")
    }

    private fun gunzip(bytes: ByteArray): JsonObject =
        Json.parseToJsonElement(GZIPInputStream(bytes.inputStream()).readBytes().decodeToString()).jsonObject

    private fun mimirBlock(
        tenant: String,
        ulid: String,
    ) {
        store.put(
            "mimir/$tenant/$ulid/meta.json",
            """{"ulid":"$ulid","minTime":1,"maxTime":2,"compaction":{"level":1},"thanos":{"labels":{},"segment_files":["000001"]}}""",
        )
        store.put("mimir/$tenant/$ulid/index", "index of $ulid")
        store.put("mimir/$tenant/$ulid/chunks/000001", "chunks of $ulid")
    }

    private fun mimirIndex(
        tenant: String,
        blocks: List<String>,
        marked: List<String> = emptyList(),
    ) = putGzip(
        "mimir/$tenant/bucket-index.json.gz",
        """{"version":2,"blocks":[${blocks.joinToString(",") { """{"block_id":"$it","min_time":1,"max_time":2}""" }}],
            "block_deletion_marks":[${marked.joinToString(",") { """{"block_id":"$it","deletion_time":1}""" }}],"updated_at":1}""",
    )

    private fun tempoBlock(
        tenant: String,
        id: String,
        compacted: Boolean = false,
    ) {
        store.put("tempo/$tenant/$id/data.parquet", "spans of $id")
        store.put("tempo/$tenant/$id/meta.json", """{"blockID":"$id","tenantID":"$tenant"}""")
        if (compacted) store.put("tempo/$tenant/$id/meta.compacted.json", """{"blockID":"$id"}""")
    }

    /** acme and beta, each with every signal, and acme's reports and annotations. */
    private fun twoTenants() {
        listOf("acme", "beta").forEach { tenant ->
            mimirBlock(tenant, "01A$tenant")
            mimirIndex(tenant, listOf("01A$tenant"))
            tempoBlock(tenant, "t-$tenant")
            store.put("loki/$tenant/fp1/chunk-1", "log chunk of $tenant")
            store.put("loki/index/index_20376/$tenant/compacted-$tenant.tsdb.gz", "index of $tenant")
            store.put("grafana/annotations/$tenant/20261015-100000_c-$tenant.json", "[]")
            store.put("reports/$tenant/exp/index.html", "pointer of $tenant")
        }
        store.put("loki/index/index_20376/ingester-1.tsdb.gz", "multi-tenant head index")
        store.put("tempo/acme/index.json.gz", "the live tenant index")
        store.put("tempo/acme/index.pb.zst", "the live tenant index")
    }

    @Test
    fun `the zip holds the tenant's store under data and nothing of another tenant`() {
        twoTenants()

        run()

        assertThat(entries.keys).containsExactlyInAnyOrder(
            "data/loki/index/index_20376/acme/compacted-acme.tsdb.gz",
            "data/loki/index/index_20376/ingester-1.tsdb.gz",
            "data/tempo/acme/t-acme/data.parquet",
            "data/tempo/acme/t-acme/meta.json",
            "data/tempo/acme/index.json.gz",
            "data/mimir/acme/01Aacme/meta.json",
            "data/mimir/acme/01Aacme/index",
            "data/mimir/acme/01Aacme/chunks/000001",
            "data/mimir/acme/bucket-index.json.gz",
            "data/loki/acme/fp1/Y2h1bmstMQ==",
            "data/grafana/annotations/acme/20261015-100000_c-acme.json",
            "data/reports/acme/exp/index.html",
        )
        assertThat(entries["data/loki/acme/fp1/Y2h1bmstMQ=="]?.decodeToString()).isEqualTo("log chunk of acme")
    }

    @Test
    fun `the copy runs in the safe order - Loki's index, Tempo, Mimir with its index last, then the immutable objects`() {
        twoTenants()

        run()

        val order = entries.keys.toList()

        fun last(prefix: String) = order.indexOfLast { it.startsWith(prefix) }

        fun first(prefix: String) = order.indexOfFirst { it.startsWith(prefix) }
        assertThat(last("data/loki/index/")).isLessThan(first("data/tempo/"))
        assertThat(last("data/tempo/")).isLessThan(first("data/mimir/"))
        assertThat(order.last { it.startsWith("data/mimir/") }).isEqualTo("data/mimir/acme/bucket-index.json.gz")
        assertThat(last("data/mimir/")).isLessThan(first("data/loki/acme/"))
        assertThat(first("data/loki/acme/")).isLessThan(first("data/grafana/"))
        assertThat(first("data/grafana/")).isLessThan(first("data/reports/"))
    }

    @Test
    fun `Mimir blocks come from one read of the bucket index - a marked block and a block the index does not list are left out`() {
        listOf("01A", "01B", "01C").forEach { mimirBlock("acme", it) }
        mimirIndex("acme", blocks = listOf("01A", "01B"), marked = listOf("01B"))

        run()

        val index = gunzip(entries.getValue("data/mimir/acme/bucket-index.json.gz"))
        assertThat(
            index.getValue("blocks").jsonArray.map {
                it.jsonObject
                    .getValue("block_id")
                    .jsonPrimitive.content
            },
        ).containsExactly("01A")
        assertThat(
            entries.keys
                .filter { it.startsWith("data/mimir/acme/01") }
                .map { it.split('/')[3] }
                .distinct(),
        ).containsExactly("01A")
        assertThat(store.opened.count { it == "mimir/acme/bucket-index.json.gz" }).isEqualTo(1)
    }

    @Test
    fun `a block merged during the copy is not lost - the sources the index listed are copied and indexed`() {
        listOf("01A", "01B").forEach { mimirBlock("acme", it) }
        mimirIndex("acme", blocks = listOf("01A", "01B"))
        store.beforeOpen = { key ->
            if (key == "mimir/acme/01A/meta.json") {
                // The compactor merges 01A and 01B into 01M and marks both, and rewrites the index.
                mimirBlock("acme", "01M")
                store.put("mimir/acme/01A/deletion-mark.json", "{}")
                store.put("mimir/acme/01B/deletion-mark.json", "{}")
                mimirIndex("acme", blocks = listOf("01A", "01B", "01M"), marked = listOf("01A", "01B"))
            }
        }

        run()

        val index = gunzip(entries.getValue("data/mimir/acme/bucket-index.json.gz"))
        assertThat(
            index.getValue("blocks").jsonArray.map {
                it.jsonObject
                    .getValue("block_id")
                    .jsonPrimitive.content
            },
        ).containsExactlyInAnyOrder("01A", "01B")
        assertThat(entries.keys).noneMatch { it.endsWith("deletion-mark.json") || it.contains("/01M/") }
    }

    @Test
    fun `Tempo blocks with a meta json and no compacted marker are copied, with the zip's own tenant index of exactly them`() {
        tempoBlock("acme", "t1")
        tempoBlock("acme", "t2", compacted = true)
        store.put("tempo/acme/t3/data.parquet", "a block still being written: no meta.json yet")
        store.put("tempo/acme/index.json.gz", "the live index")

        run()

        assertThat(entries.keys.filter { it.startsWith("data/tempo/") })
            .containsExactlyInAnyOrder("data/tempo/acme/t1/data.parquet", "data/tempo/acme/t1/meta.json", "data/tempo/acme/index.json.gz")
        val index = gunzip(entries.getValue("data/tempo/acme/index.json.gz"))
        assertThat(
            index.getValue("meta").jsonArray.map {
                it.jsonObject
                    .getValue("blockID")
                    .jsonPrimitive.content
            },
        ).containsExactly("t1")
    }

    @Test
    fun `a Tempo block merged during the copy is not lost - the listed blocks are copied whole and indexed, the merged one left out`() {
        tempoBlock("acme", "t1")
        tempoBlock("acme", "t2")
        store.beforeOpen = { key ->
            if (key == "tempo/acme/t1/meta.json") {
                // The compactor merges t1 and t2 into tM and marks both compacted.
                tempoBlock("acme", "tM")
                store.put("tempo/acme/t1/meta.compacted.json", """{"blockID":"t1"}""")
                store.put("tempo/acme/t2/meta.compacted.json", """{"blockID":"t2"}""")
            }
        }

        run()

        assertThat(entries.keys.filter { it.startsWith("data/tempo/") }).containsExactlyInAnyOrder(
            "data/tempo/acme/t1/data.parquet",
            "data/tempo/acme/t1/meta.json",
            "data/tempo/acme/t2/data.parquet",
            "data/tempo/acme/t2/meta.json",
            "data/tempo/acme/index.json.gz",
        )
        val index = gunzip(entries.getValue("data/tempo/acme/index.json.gz"))
        assertThat(
            index.getValue("meta").jsonArray.map {
                it.jsonObject
                    .getValue("blockID")
                    .jsonPrimitive.content
            },
        ).containsExactlyInAnyOrder("t1", "t2")
        assertThat(index.getValue("compacted").jsonArray).isEmpty()
    }

    @Test
    fun `a Loki index table replaced during the copy is listed and copied again, and the zip holds only the new listing`() {
        store.put("loki/acme/fp/chunk", "c")
        store.put("loki/index/index_1/ingester-a.tsdb.gz", "head a")
        store.put("loki/index/index_1/ingester-b.tsdb.gz", "head b")
        store.beforeOpen = { key ->
            if (key == "loki/index/index_1/ingester-b.tsdb.gz" && "loki/index/index_1/acme/compacted.tsdb.gz" !in store.objects) {
                // The compactor writes the tenant's compacted file and removes the two it merged.
                store.put("loki/index/index_1/acme/compacted.tsdb.gz", "compacted")
                store.objects.remove("loki/index/index_1/ingester-a.tsdb.gz")
                store.objects.remove("loki/index/index_1/ingester-b.tsdb.gz")
            }
        }

        run()

        assertThat(entries.keys.filter { it.startsWith("data/loki/index/") })
            .containsExactly("data/loki/index/index_1/acme/compacted.tsdb.gz")
    }

    /**
     * Loki's filesystem store keeps a v13 chunk under `<tenant>/<fingerprint>/` with its last key part
     * base64-encoded, where S3 has it as it is; the offline Loki reads the zip as a filesystem store.
     * The pair below is what Loki 3.7.8 wrote to disk for a chunk named by that key.
     */
    @Test
    fun `a Loki chunk is named as Loki's filesystem store names it, so the offline Loki finds it`() {
        store.put("loki/acme/4dfd726b66f828a3/1a142e71190:1a142e71190:e72c7c6c", "chunk")
        // Base64 of "???" is "Pz8/": Loki's file path drops the trailing separator, and so does the zip.
        store.put("loki/acme/fp/???", "other chunk")

        run()

        assertThat(entries.keys.filter { it.startsWith("data/loki/acme/") }).containsExactlyInAnyOrder(
            "data/loki/acme/4dfd726b66f828a3/MWExNDJlNzExOTA6MWExNDJlNzExOTA6ZTcyYzdjNmM=",
            "data/loki/acme/fp/Pz8",
        )
        assertThat(entries["data/loki/acme/fp/Pz8"]?.decodeToString()).isEqualTo("other chunk")
    }

    @Test
    fun `a tenant with no metrics, logs or traces fails, naming the tenant and the prefixes it looked under`() {
        twoTenants()

        assertThatThrownBy { copy.plan("ghost") }
            .isInstanceOf(EmptyTenantException::class.java)
            .hasMessageContaining("ghost")
            .hasMessageContaining("mimir/ghost/")
            .hasMessageContaining("loki/ghost/")
            .hasMessageContaining("tempo/ghost/")
    }

    @Test
    fun `the plan sums every object it will copy`() {
        twoTenants()

        val plan = copy.plan("acme")

        val acme =
            store.objects.filterKeys { key ->
                listOf(
                    "mimir/acme/01",
                    "tempo/acme/t-",
                    "loki/acme/",
                    "loki/index/index_20376/acme/",
                    "grafana/annotations/acme/",
                    "reports/acme/",
                ).any { key.startsWith(it) } ||
                    key == "loki/index/index_20376/ingester-1.tsdb.gz"
            }
        assertThat(plan.bytes).isEqualTo(acme.values.sumOf { it.first.size.toLong() })
    }

    @Test
    fun `an object that disappears from a Tempo block fails the copy naming traces and the step`() {
        tempoBlock("acme", "t1")
        store.beforeOpen = { key -> if (key == "tempo/acme/t1/data.parquet") store.objects.remove(key) }

        assertThatThrownBy { run() }
            .isInstanceOfSatisfying(ExportStepFailed::class.java) { failure ->
                assertThat(failure.signal).isEqualTo(ExportSignal.TRACES)
                assertThat(failure.step).contains("t1")
            }
    }

    @Test
    fun `Mimir blocks with no bucket index fail the metrics copy instead of guessing which blocks are live`() {
        mimirBlock("acme", "01A")

        assertThatThrownBy { copy.plan("acme") }
            .isInstanceOfSatisfying(ExportStepFailed::class.java) { failure ->
                assertThat(failure.signal).isEqualTo(ExportSignal.METRICS)
                assertThat(failure.cause).hasMessageContaining("bucket-index.json.gz")
            }
    }
}
