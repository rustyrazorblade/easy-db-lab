package com.rustyrazorblade.easydblab.compactorlease

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

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

    @Test
    fun `a tree many levels deep is emptied and the scratch directory kept`() {
        val scratch = Files.createDirectories(root.resolve("tempo"))
        val deepest = Files.createDirectories(scratch.resolve("a/b/c/d/e/f/g/h"))
        deepest.resolve("block").toFile().writeText("x")
        scratch.resolve("a/b/c/side").toFile().writeText("y")

        ScratchWipe(listOf(scratch)).run()

        assertThat(scratch).isEmptyDirectory()
    }

    @Test
    fun `a symbolic link to a file outside the scratch directory is removed and the file kept`() {
        val target = Files.createDirectories(root.resolve("outside")).resolve("keep")
        target.toFile().writeText("keep")
        val scratch = Files.createDirectories(root.resolve("loki"))
        Files.createSymbolicLink(Files.createDirectories(scratch.resolve("nested")).resolve("link"), target)

        ScratchWipe(listOf(scratch)).run()

        assertThat(scratch).isEmptyDirectory()
        assertThat(target).hasContent("keep")
    }

    @Test
    fun `a symbolic link to a directory is removed and the directory's tree untouched`() {
        val outside = Files.createDirectories(root.resolve("outside/sub"))
        outside.resolve("keep").toFile().writeText("keep")
        val scratch = Files.createDirectories(root.resolve("mimir"))
        Files.createSymbolicLink(Files.createDirectories(scratch.resolve("nested")).resolve("link"), outside.parent)

        ScratchWipe(listOf(scratch)).run()

        assertThat(scratch).isEmptyDirectory()
        assertThat(outside.resolve("keep")).hasContent("keep")
    }

    @Test
    fun `a wipe that cannot delete an entry throws`() {
        val scratch = Files.createDirectories(root.resolve("mimir"))
        val locked = Files.createDirectories(scratch.resolve("locked"))
        locked.resolve("stale").toFile().writeText("x")
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assertThatThrownBy { ScratchWipe(listOf(scratch)).run() }.isInstanceOf(IOException::class.java)
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }
}
