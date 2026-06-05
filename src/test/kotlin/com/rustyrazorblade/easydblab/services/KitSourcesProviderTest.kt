package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.TestContextFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KitSourcesProviderTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var provider: KitSourcesProvider

    @BeforeEach
    fun setup() {
        val context = TestContextFactory.createTestContext(tempDir)
        provider = KitSourcesProvider(context)
    }

    @Test
    fun `load returns empty config when kit-sources yaml does not exist`() {
        assertThat(provider.load().sources).isEmpty()
    }

    @Test
    fun `add returns ADDED for new source`() {
        val dir = File(tempDir, "kits").also { it.mkdirs() }
        val result = provider.add("myproject", dir.absolutePath)
        assertThat(result).isEqualTo(AddSourceResult.ADDED)
        val sources = provider.load().sources
        assertThat(sources).hasSize(1)
        assertThat(sources[0].name).isEqualTo("myproject")
        assertThat(sources[0].path).isEqualTo(dir.canonicalPath)
    }

    @Test
    fun `add returns UPDATED when name already exists and updates the path`() {
        val dir1 = File(tempDir, "kits1").also { it.mkdirs() }
        val dir2 = File(tempDir, "kits2").also { it.mkdirs() }
        provider.add("myproject", dir1.absolutePath)
        val result = provider.add("myproject", dir2.absolutePath)
        assertThat(result).isEqualTo(AddSourceResult.UPDATED)
        val sources = provider.load().sources
        assertThat(sources).hasSize(1)
        assertThat(sources[0].path).isEqualTo(dir2.canonicalPath)
    }

    @Test
    fun `add preserves registration order for multiple sources`() {
        val dir1 = File(tempDir, "kits1").also { it.mkdirs() }
        val dir2 = File(tempDir, "kits2").also { it.mkdirs() }
        provider.add("first", dir1.absolutePath)
        provider.add("second", dir2.absolutePath)
        val names = provider.load().sources.map { it.name }
        assertThat(names).containsExactly("first", "second")
    }

    @Test
    fun `add stores path as canonical form`() {
        val dir = File(tempDir, "kits").also { it.mkdirs() }
        provider.add("myproject", dir.absolutePath)
        assertThat(provider.load().sources[0].path).isEqualTo(dir.canonicalPath)
    }

    @Test
    fun `remove returns true and deletes source`() {
        val dir = File(tempDir, "kits").also { it.mkdirs() }
        provider.add("myproject", dir.absolutePath)
        val removed = provider.remove("myproject")
        assertThat(removed).isTrue()
        assertThat(provider.load().sources).isEmpty()
    }

    @Test
    fun `remove returns false for unknown name`() {
        val removed = provider.remove("doesnotexist")
        assertThat(removed).isFalse()
    }

    @Test
    fun `changes persist across load calls`() {
        val dir1 = File(tempDir, "kits1").also { it.mkdirs() }
        val dir2 = File(tempDir, "kits2").also { it.mkdirs() }
        provider.add("first", dir1.absolutePath)
        provider.add("second", dir2.absolutePath)
        provider.remove("first")
        val names = provider.load().sources.map { it.name }
        assertThat(names).containsExactly("second")
    }
}
