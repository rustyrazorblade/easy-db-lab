package com.rustyrazorblade.easydblab.commands.kit.source

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Tests for kit source subcommands. Uses real KitSourcesProvider with the BaseKoinTest temp
 * directory so the full add/list/remove cycle exercises real disk I/O without mocking.
 */
class KitSourceCommandsTest : BaseKoinTest() {
    private lateinit var provider: KitSourcesProvider

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { KitSourcesProvider(get()) }
            },
        )

    @BeforeEach
    fun setupProvider() {
        provider = get()
    }

    private fun captureOutput(block: () -> Unit): String {
        val out = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(out))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return out.toString().trim()
    }

    // ── KitSourceAdd ──────────────────────────────────────────────────────

    @Test
    fun `add prints confirmation and persists new named source`() {
        val dir = File(tempDir, "kits").also { it.mkdirs() }
        val cmd =
            KitSourceAdd().also {
                it.name = "myproject"
                it.path = dir.absolutePath
            }
        val output = captureOutput { cmd.run() }
        assertThat(output).contains("Added kit source 'myproject'")
        val sources = provider.load().sources
        assertThat(sources).hasSize(1)
        assertThat(sources[0].name).isEqualTo("myproject")
        assertThat(sources[0].path).isEqualTo(dir.canonicalPath)
    }

    @Test
    fun `add prints updated message when name already exists`() {
        val dir1 = File(tempDir, "kits1").also { it.mkdirs() }
        val dir2 = File(tempDir, "kits2").also { it.mkdirs() }
        provider.add("myproject", dir1.absolutePath)
        val cmd =
            KitSourceAdd().also {
                it.name = "myproject"
                it.path = dir2.absolutePath
            }
        val output = captureOutput { cmd.run() }
        assertThat(output).contains("Updated kit source 'myproject'")
        assertThat(provider.load().sources).hasSize(1)
        assertThat(provider.load().sources[0].path).isEqualTo(dir2.canonicalPath)
    }

    @Test
    fun `add throws for non-existent path`() {
        val cmd =
            KitSourceAdd().also {
                it.name = "bad"
                it.path = "/does/not/exist/at/all"
            }
        assertThatThrownBy { cmd.run() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("/does/not/exist/at/all")
    }

    // ── KitSourceList ────────────────────────────────────────────────────

    @Test
    fun `list prints no sources message when empty`() {
        val output = captureOutput { KitSourceList().run() }
        assertThat(output).contains("No kit sources registered")
    }

    @Test
    fun `list prints name and path for registered sources`() {
        val dir = File(tempDir, "kits").also { it.mkdirs() }
        provider.add("myproject", dir.absolutePath)
        val output = captureOutput { KitSourceList().run() }
        assertThat(output).contains("myproject")
        assertThat(output).contains(dir.canonicalPath)
    }

    @Test
    fun `list flags missing path`() {
        provider.add("ghost", "/nonexistent/path/for/test")
        val output = captureOutput { KitSourceList().run() }
        assertThat(output).contains("[missing]")
    }

    @Test
    fun `list does not flag existing path as missing`() {
        val dir = File(tempDir, "kits").also { it.mkdirs() }
        provider.add("myproject", dir.absolutePath)
        val output = captureOutput { KitSourceList().run() }
        assertThat(output).doesNotContain("[missing]")
    }

    // ── KitSourceRemove ──────────────────────────────────────────────────

    @Test
    fun `remove prints confirmation and deletes source`() {
        val dir = File(tempDir, "kits").also { it.mkdirs() }
        provider.add("myproject", dir.absolutePath)
        val cmd = KitSourceRemove().also { it.name = "myproject" }
        val output = captureOutput { cmd.run() }
        assertThat(output).contains("Removed kit source 'myproject'")
        assertThat(provider.load().sources).isEmpty()
    }

    @Test
    fun `remove prints not found for unknown name`() {
        val cmd = KitSourceRemove().also { it.name = "doesnotexist" }
        val output = captureOutput { cmd.run() }
        assertThat(output).contains("No kit source named 'doesnotexist'")
    }
}
