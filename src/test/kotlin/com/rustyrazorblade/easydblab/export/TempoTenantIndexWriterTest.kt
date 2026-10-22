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
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream

/**
 * The zip's own Tempo tenant index, `index.json.gz`: `{created_at, meta: [...], compacted: []}` from
 * the `meta.json` of every block in the tenant directory, which a split Tempo reader reads (spike S5).
 */
class TempoTenantIndexWriterTest {
    @TempDir
    lateinit var tenantDir: File

    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val writer = TempoTenantIndexWriter(Clock.fixed(now, ZoneOffset.UTC))

    private fun meta(id: String) = """{"blockID":"$id","tenantID":"acme","startTime":"2026-10-15T10:00:00Z","totalObjects":2}"""

    private fun block(
        id: String,
        withMeta: Boolean = true,
    ) {
        File(tenantDir, id).mkdirs()
        File(tenantDir, "$id/data.parquet").writeText("p")
        if (withMeta) File(tenantDir, "$id/meta.json").writeText(meta(id))
    }

    private fun decode(bytes: ByteArray): JsonObject =
        Json.parseToJsonElement(GZIPInputStream(bytes.inputStream()).readBytes().decodeToString()).jsonObject

    @Test
    fun `the index holds every block's meta json as written, no compacted blocks and its creation time`() {
        block("b1")
        block("b2")

        val index = decode(writer.write(FileBlockDirectory(tenantDir)))

        assertThat(index.getValue("created_at").jsonPrimitive.content).isEqualTo("2026-10-15T12:00:00Z")
        assertThat(index.getValue("compacted").jsonArray).isEmpty()
        assertThat(index.getValue("meta").jsonArray.map { it.jsonObject })
            .containsExactlyInAnyOrder(Json.parseToJsonElement(meta("b1")).jsonObject, Json.parseToJsonElement(meta("b2")).jsonObject)
    }

    @Test
    fun `an index that leaves out a block, or lists one that is not there, is rejected`() {
        block("b1")
        block("b2")
        val directory = FileBlockDirectory(tenantDir)
        val index = writer.index(directory)

        assertThatThrownBy { writer.verify(index.copy(meta = index.meta.take(1)), directory) }
            .isInstanceOf(IndexMismatchException::class.java)
            .hasMessageContaining("leaves out")
        assertThatThrownBy { writer.verify(index.copy(meta = index.meta + Json.parseToJsonElement(meta("b9")).jsonObject), directory) }
            .isInstanceOf(IndexMismatchException::class.java)
            .hasMessageContaining("b9")
    }

    @Test
    fun `a block with no meta json fails the index`() {
        block("b1")
        block("b2", withMeta = false)

        assertThatThrownBy { writer.write(FileBlockDirectory(tenantDir)) }
            .isInstanceOf(IndexMismatchException::class.java)
            .hasMessageContaining("b2")
    }
}
