package com.rustyrazorblade.easydblab.compactorlease

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The wipe on acquire: every scratch directory is emptied and kept, so its host-path mount stays. */
class ScratchWipeTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `every scratch directory is emptied, nested content included, and kept`() {
        val mimir = Files.createDirectories(root.resolve("mimir"))
        val tempo = Files.createDirectories(root.resolve("tempo"))
        Files
            .createDirectories(mimir.resolve("compactor/01ABC"))
            .resolve("index")
            .toFile()
            .writeText("x")
        tempo.resolve("scheduler.json").toFile().writeText("{}")

        ScratchWipe(listOf(mimir, tempo)).run()

        assertThat(mimir).isEmptyDirectory()
        assertThat(tempo).isEmptyDirectory()
    }

    @Test
    fun `a scratch directory that does not exist is created`() {
        val loki = root.resolve("loki")

        ScratchWipe(listOf(loki)).run()

        assertThat(loki).isEmptyDirectory()
    }

    @Test
    fun `a symbolic link in a scratch directory is removed, not followed`() {
        val outside = Files.createDirectories(root.resolve("outside")).also { it.resolve("keep").toFile().writeText("keep") }
        val scratch = Files.createDirectories(root.resolve("mimir"))
        Files.createSymbolicLink(scratch.resolve("link"), outside)

        ScratchWipe(listOf(scratch)).run()

        assertThat(scratch).isEmptyDirectory()
        assertThat(outside.resolve("keep")).exists()
    }
}
