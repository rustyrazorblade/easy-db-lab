package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files

/**
 * Guards the [Context] workspace rules: the workspace directory is absolute, so workspace files
 * never follow the process's current directory, and [Context.workspaceFile] only ever returns a
 * path strictly inside the workspace, so a typed name cannot reach the workspace itself or above it.
 */
class ContextTest {
    @TempDir
    lateinit var tempDir: File

    private fun context(): Context = Context(tempDir, isInteractive = false, workingDirectory = File(tempDir, "workspace"))

    @Test
    fun `a relative workspace directory is rejected with a message naming it`() {
        assertThatThrownBy {
            Context(tempDir, isInteractive = false, workingDirectory = File("relative/workspace"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be absolute")
            .hasMessageContaining("relative/workspace")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", ".", "./", "..", "../x", "a/..", "a/../..", "a/../../b", "/etc/passwd"])
    fun `a name that is not strictly inside the workspace is refused`(name: String) {
        assertThatThrownBy { context().workspaceFile(name) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("inside the workspace")
    }

    @ParameterizedTest
    @ValueSource(strings = ["state.json", ".socks5-proxy-state", "bin/tunnel", "5.0", "a/../b"])
    fun `a name inside the workspace resolves under the workspace directory`(name: String) {
        val context = context()

        val resolved = context.workspaceFile(name).toPath().normalize()

        assertThat(resolved).startsWithRaw(context.workingDirectory.toPath())
        assertThat(resolved).isNotEqualTo(context.workingDirectory.toPath())
    }

    @ParameterizedTest
    @ValueSource(strings = ["out/file", "out/new", "out/../out/file", "dead/new"])
    fun `a name that leaves the workspace through a link is refused`(name: String) {
        val context = context()
        val outside = File(tempDir, "outside").apply { mkdirs() }
        File(outside, "file").writeText("outside")
        context.workingDirectory.mkdirs()
        Files.createSymbolicLink(File(context.workingDirectory, "out").toPath(), outside.toPath())
        Files.createSymbolicLink(File(context.workingDirectory, "dead").toPath(), File(outside, "missing").toPath())

        assertThatThrownBy { context.workspaceFile(name) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("inside the workspace")
    }

    @Test
    fun `a link as the last segment resolves to the link itself, so a caller can unlink it`() {
        val context = context()
        val outside = File(tempDir, "outside").apply { mkdirs() }
        context.workingDirectory.mkdirs()
        Files.createSymbolicLink(File(context.workingDirectory, "out").toPath(), outside.toPath())

        assertThat(context.workspaceFile("out")).isEqualTo(File(context.workingDirectory, "out"))
    }

    @Test
    fun `a link that stays inside the workspace is allowed`() {
        val context = context()
        val target = File(context.workingDirectory, "real").apply { mkdirs() }
        Files.createSymbolicLink(File(context.workingDirectory, "alias").toPath(), target.toPath())

        assertThat(context.workspaceFile("alias/file")).isEqualTo(File(context.workingDirectory, "alias/file"))
    }

    @ParameterizedTest(name = "[{0}] is profile {1}")
    @CsvSource(", default", "'', default", "lab, lab")
    fun `EASY_DB_LAB_PROFILE names the profile, and unset or empty means default`(
        envValue: String?,
        profile: String,
    ) {
        assertThat(Context.profileName(envValue)).isEqualTo(profile)
    }
}
