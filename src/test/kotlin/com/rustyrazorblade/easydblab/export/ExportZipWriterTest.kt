package com.rustyrazorblade.easydblab.export

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * The export's zip is written under a temporary name beside the path and moved to the path only when
 * complete, never over an existing file. On any failure nothing is left at the path, and the
 * temporary file, the tool's own scratch, is removed.
 */
class ExportZipWriterTest {
    @TempDir
    lateinit var dir: File

    private val writer = ExportZipWriter()

    private fun entries(zip: File): Map<String, String> =
        ZipFile(zip).use { file ->
            file.entries().asSequence().associate { it.name to file.getInputStream(it).readBytes().decodeToString() }
        }

    @Test
    fun `a path in a directory that does not exist is refused`() {
        assertThatThrownBy { ExportTarget.of(File(dir, "no-such-dir/out.zip")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no-such-dir")
    }

    @Test
    fun `an existing path is refused and left unchanged`() {
        val existing = File(dir, "out.zip").apply { writeText("keep me") }

        assertThatThrownBy { ExportTarget.of(existing) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("already exists")
        assertThat(existing.readText()).isEqualTo("keep me")
    }

    @Test
    fun `a path that does not end in zip is refused`() {
        assertThatThrownBy { ExportTarget.of(File(dir, "out")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(".zip")
    }

    @Test
    fun `every entry sits in one directory named after the zip, so unzipping makes the stack directory`() {
        val target = ExportTarget.of(File(dir, "lab-export.zip"))

        writer.write(target) { sink ->
            sink.put("compose.yml", "services: {}".byteInputStream())
            sink.put("data/mimir/acme/01A/meta.json", "{}".byteInputStream())
        }

        assertThat(entries(target.zip))
            .containsOnlyKeys("lab-export/compose.yml", "lab-export/data/mimir/acme/01A/meta.json")
            .containsEntry("lab-export/compose.yml", "services: {}")
        assertThat(dir.list()).containsExactly("lab-export.zip")
    }

    @Test
    fun `the sink knows what it wrote and how many bytes each entry holds`() {
        val target = ExportTarget.of(File(dir, "out.zip"))

        val written = writer.write(target) { sink -> sink.put("data/a", "12345".byteInputStream()).also { assertThat(it).isEqualTo(5) } }

        assertThat(written).isEqualTo(5)
    }

    @Test
    fun `a failure mid-copy leaves no zip at the path and removes the partial file`() {
        val target = ExportTarget.of(File(dir, "out.zip"))

        assertThatThrownBy {
            writer.write(target) { sink ->
                sink.put("data/a", "first".byteInputStream())
                sink.put("data/b", FailingStream())
            }
        }.isInstanceOf(ExportStepFailed::class.java).hasCauseInstanceOf(IOException::class.java)

        assertThat(target.zip).doesNotExist()
        assertThat(dir.list()).isEmpty()
    }

    @Test
    fun `a file that appears at the path while the zip is written is not overwritten`() {
        val target = ExportTarget.of(File(dir, "out.zip"))

        assertThatThrownBy {
            writer.write(target) { sink ->
                sink.put("data/a", "mine".byteInputStream())
                target.zip.writeText("someone else's")
            }
        }.isInstanceOfSatisfying(ExportStepFailed::class.java) { failed ->
            assertThat(failed.signal).isEqualTo(ExportSignal.STACK)
            assertThat(failed.step).contains("move")
            assertThat(failed.cause).isInstanceOf(IOException::class.java).hasMessageContaining("appeared while the export was written")
        }

        assertThat(target.zip.readText()).isEqualTo("someone else's")
        assertThat(dir.list()).containsExactly("out.zip")
    }

    @Test
    fun `an entry path that leaves the stack directory is refused`() {
        val target = ExportTarget.of(File(dir, "out.zip"))

        assertThatThrownBy { writer.write(target) { sink -> sink.put("../escape", "x".byteInputStream()) } }
            .isInstanceOf(ExportStepFailed::class.java)
            .hasCauseInstanceOf(IllegalArgumentException::class.java)
        assertThat(dir.list()).isEmpty()
    }

    @Test
    fun `the partial file sits beside the path, named after it`() {
        val target = ExportTarget.of(File(dir, "out.zip"))
        var partial: List<String> = emptyList()

        writer.write(target) { partial = dir.list()!!.toList() }

        assertThat(partial).singleElement().asString().startsWith("out.zip.partial-")
    }

    /** A source that breaks after a few bytes, as a dropped S3 connection does. */
    private class FailingStream : java.io.InputStream() {
        private var left = 3

        override fun read(): Int = if (left-- > 0) 'x'.code else throw IOException("connection reset")
    }
}
