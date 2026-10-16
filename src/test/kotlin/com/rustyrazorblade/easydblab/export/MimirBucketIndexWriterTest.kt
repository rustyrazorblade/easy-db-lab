package com.rustyrazorblade.easydblab.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream

/**
 * The zip's own Mimir bucket index (version 2, the field set of Mimir 3.2.1's
 * `bucketindex.BlockFromThanosMeta`), written from the tenant directory it indexes. It must list
 * exactly the blocks there: one left out is a silent gap in every query, and one that is not there
 * fails every query with Mimir's store consistency check (spike S4).
 */
class MimirBucketIndexWriterTest {
    @TempDir
    lateinit var tenantDir: File

    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val writer = MimirBucketIndexWriter(Clock.fixed(now, ZoneOffset.UTC))

    private fun meta(
        ulid: String,
        thanos: String = """{"labels":{},"source":"receive","segment_files":["000001","000002"]}""",
        level: Int = 1,
        extra: String = "",
    ) = """{"ulid":"$ulid","minTime":1000,"maxTime":61000,"stats":{"numSeries":3},"compaction":{"level":$level,"sources":["$ulid"]$extra},"version":1,"thanos":$thanos}"""

    private fun block(
        ulid: String,
        meta: String? = meta(ulid),
        uploadedAt: Instant = Instant.parse("2026-10-15T11:00:00Z"),
    ) {
        File(tenantDir, "$ulid/chunks").mkdirs()
        File(tenantDir, "$ulid/index").writeText("index")
        File(tenantDir, "$ulid/chunks/000001").writeText("c")
        meta?.let { File(tenantDir, "$ulid/meta.json").apply { writeText(it) }.toPath().also { p -> java.nio.file.Files.setLastModifiedTime(p, FileTime.from(uploadedAt)) } }
    }

    private fun decode(bytes: ByteArray): JsonObject =
        Json.parseToJsonElement(GZIPInputStream(bytes.inputStream()).readBytes().decodeToString()).jsonObject

    private fun entries(index: JsonObject): Map<String, JsonObject> =
        index.getValue("blocks").jsonArray.associate { it.jsonObject.getValue("block_id").jsonPrimitive.content to it.jsonObject }

    @Test
    fun `each block's entry carries Mimir's fields, from its meta json and the time the meta was written`() {
        block("01AAA")

        val index = decode(writer.write(FileBlockDirectory(tenantDir)))
        val entry = entries(index).getValue("01AAA")

        assertThat(index.getValue("version").jsonPrimitive.int).isEqualTo(2)
        assertThat(index.getValue("block_deletion_marks").jsonArray).isEmpty()
        assertThat(index.getValue("updated_at").jsonPrimitive.long).isEqualTo(now.epochSecond)
        assertThat(entry.getValue("min_time").jsonPrimitive.long).isEqualTo(1000)
        assertThat(entry.getValue("max_time").jsonPrimitive.long).isEqualTo(61000)
        assertThat(entry.getValue("segments_format").jsonPrimitive.content).isEqualTo("1b6d")
        assertThat(entry.getValue("segments_num").jsonPrimitive.int).isEqualTo(2)
        assertThat(entry.getValue("uploaded_at").jsonPrimitive.long).isEqualTo(Instant.parse("2026-10-15T11:00:00Z").epochSecond)
        assertThat(entry.getValue("source").jsonPrimitive.content).isEqualTo("receive")
        assertThat(entry.getValue("compaction_level").jsonPrimitive.int).isEqualTo(1)
        // Omitted when empty, as Mimir's omitempty does.
        assertThat(entry.keys).doesNotContain("labels", "compactor_shard_id", "out_of_order")
    }

    @Test
    fun `segments come from the chunk files the meta lists when it names no segment files, and only when they count up from 000001`() {
        block("01AAA", meta("01AAA", thanos = """{"labels":{},"files":[{"rel_path":"chunks/000001"},{"rel_path":"chunks/000002"},{"rel_path":"index"}]}"""))
        block("01BBB", meta("01BBB", thanos = """{"labels":{},"segment_files":["000001","000003"]}"""))

        val entries = entries(decode(writer.write(FileBlockDirectory(tenantDir))))

        assertThat(entries.getValue("01AAA").getValue("segments_num").jsonPrimitive.int).isEqualTo(2)
        assertThat(entries.getValue("01BBB").keys).doesNotContain("segments_format", "segments_num")
    }

    @Test
    fun `external labels, the compactor shard and an out-of-order block are kept`() {
        block(
            "01AAA",
            meta(
                "01AAA",
                thanos = """{"labels":{"__compactor_shard_id__":"1_of_4","zone":"a"},"source":"compactor","segment_files":["000001"]}""",
                level = 3,
                extra = ""","hints":["from-out-of-order"]""",
            ),
        )

        val entry = entries(decode(writer.write(FileBlockDirectory(tenantDir)))).getValue("01AAA")

        assertThat(entry.getValue("compactor_shard_id").jsonPrimitive.content).isEqualTo("1_of_4")
        assertThat(entry.getValue("labels").jsonObject.keys).containsExactlyInAnyOrder("__compactor_shard_id__", "zone")
        assertThat(entry.getValue("out_of_order").jsonPrimitive.content).isEqualTo("true")
        assertThat(entry.getValue("compaction_level").jsonPrimitive.int).isEqualTo(3)
    }

    @Test
    fun `the index lists exactly the blocks in the directory`() {
        block("01AAA")
        block("01BBB")
        File(tenantDir, "markers").mkdirs()

        val index = decode(writer.write(FileBlockDirectory(tenantDir)))

        assertThat(entries(index).keys).containsExactlyInAnyOrder("01AAA", "01BBB")
    }

    @Test
    fun `an index that leaves out a block on disk is rejected`() {
        block("01AAA")
        block("01BBB")
        val directory = FileBlockDirectory(tenantDir)
        val partial = writer.index(directory).let { it.copy(blocks = it.blocks.filter { block -> block.blockId == "01AAA" }) }

        assertThatThrownBy { writer.verify(partial, directory) }
            .isInstanceOf(IndexMismatchException::class.java)
            .hasMessageContaining("01BBB")
            .hasMessageContaining("leaves out")
    }

    @Test
    fun `an index that lists a block not on disk is rejected`() {
        block("01AAA")
        val directory = FileBlockDirectory(tenantDir)
        val index = writer.index(directory)
        val extra = index.copy(blocks = index.blocks + index.blocks.single().copy(blockId = "01ZZZ"))

        assertThatThrownBy { writer.verify(extra, directory) }
            .isInstanceOf(IndexMismatchException::class.java)
            .hasMessageContaining("01ZZZ")
            .hasMessageContaining("not in")
    }

    @Test
    fun `a block directory with no meta json fails the index instead of being left out`() {
        block("01AAA")
        block("01BBB", meta = null)

        assertThatThrownBy { writer.write(FileBlockDirectory(tenantDir)) }
            .isInstanceOf(IndexMismatchException::class.java)
            .hasMessageContaining("01BBB")
    }
}
