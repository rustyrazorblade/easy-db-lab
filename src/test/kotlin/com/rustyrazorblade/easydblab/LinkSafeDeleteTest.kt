package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * [deleteWithoutFollowingLinks] removes everything at its path and never anything a link points
 * at, wherever the link sits in the tree.
 */
class LinkSafeDeleteTest {
    @TempDir
    lateinit var tempDir: File

    /**
     * Each shape puts the doomed path at `ws/target`. A link to `outside/` sits at the top, one
     * level down, or two levels down; `file` and `missing` have no link at all.
     */
    private fun build(shape: String): File {
        val outside = File(tempDir, "outside").apply { mkdirs() }
        File(outside, "precious.txt").writeText("keep")
        val target = File(tempDir, "ws/target")
        target.parentFile.mkdirs()
        when (shape) {
            "missing" -> Unit
            "file" -> target.writeText("x")
            "top-link" -> Files.createSymbolicLink(target.toPath(), outside.toPath())
            "nested-link" -> {
                File(target, "real").apply { mkdirs() }.resolve("f").writeText("x")
                Files.createSymbolicLink(File(target, "link").toPath(), outside.toPath())
            }
            "deep-link" -> {
                val deep = File(target, "a/b").apply { mkdirs() }
                Files.createSymbolicLink(File(deep, "link").toPath(), outside.toPath())
            }
            else -> error("unknown shape $shape")
        }
        return target
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "file", "top-link", "nested-link", "deep-link"])
    fun `nothing is left at the path and the link target survives`(shape: String) {
        val target = build(shape)

        assertThat(target.deleteWithoutFollowingLinks()).isTrue()

        assertThat(Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)).isFalse()
        assertThat(File(tempDir, "outside/precious.txt")).hasContent("keep")
    }
}
