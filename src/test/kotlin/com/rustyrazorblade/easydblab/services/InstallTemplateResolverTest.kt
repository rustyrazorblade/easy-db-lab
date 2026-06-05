package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.TestContextFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class InstallTemplateResolverTest : BaseKoinTest() {
    private lateinit var ctx: Context
    private lateinit var resolver: InstallTemplateResolver

    @BeforeEach
    fun setupResolver() {
        ctx = TestContextFactory.createTestContext(tempDir)
        resolver = InstallTemplateResolver(ctx, KitSourcesProvider(ctx))
    }

    private fun createProfileTemplate(name: String): File {
        val dir = File(File(ctx.profileDir, "kits"), name)
        dir.mkdirs()
        File(dir, "start.sh.template").writeText("#!/bin/bash\necho __KIT_NAME__")
        return dir
    }

    // ── listAvailableTemplates ──────────────────────────────────────────────

    @Test
    fun `lists built-in templates from classpath`() {
        val templates = resolver.listAvailableTemplates()
        assertThat(templates).contains("clickhouse", "presto")
    }

    @Test
    fun `lists profile-directory templates alongside built-ins`() {
        createProfileTemplate("mydb")

        val templates = resolver.listAvailableTemplates()
        assertThat(templates).contains("mydb", "clickhouse", "presto")
    }

    @Test
    fun `profile template overrides built-in of same name in list`() {
        createProfileTemplate("clickhouse")

        val templates = resolver.listAvailableTemplates()
        assertThat(templates.count { it == "clickhouse" }).isEqualTo(1)
    }

    @Test
    fun `returns empty list when profile install dir does not exist`() {
        // No profile templates created — should still return built-ins
        val templates = resolver.listAvailableTemplates()
        assertThat(templates).isNotEmpty()
        assertThat(templates).doesNotContain("nonexistent")
    }

    // ── resolve by name ─────────────────────────────────────────────────────

    @Test
    fun `resolves profile directory template when it exists`() {
        val dir = createProfileTemplate("mydb")

        val source = resolver.resolve("mydb")
        assertThat(source).isInstanceOf(InstallTemplateResolver.TemplateSource.Directory::class.java)
        assertThat((source as InstallTemplateResolver.TemplateSource.Directory).dir).isEqualTo(dir)
    }

    @Test
    fun `profile template takes priority over built-in of same name`() {
        createProfileTemplate("clickhouse")

        val source = resolver.resolve("clickhouse")
        assertThat(source).isInstanceOf(InstallTemplateResolver.TemplateSource.Directory::class.java)
    }

    @Test
    fun `resolves built-in template when no profile override exists`() {
        val source = resolver.resolve("clickhouse")
        assertThat(source).isInstanceOf(InstallTemplateResolver.TemplateSource.Builtin::class.java)
        assertThat((source as InstallTemplateResolver.TemplateSource.Builtin).name).isEqualTo("clickhouse")
    }

    @Test
    fun `throws when template name is not found anywhere`() {
        assertThatThrownBy { resolver.resolve("nonexistent-workload") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("nonexistent-workload")
    }

    // ── resolveAdHoc ────────────────────────────────────────────────────────

    @Test
    fun `resolveAdHoc returns Directory source for existing directory`() {
        val dir = File(tempDir, "my-templates").also { it.mkdirs() }

        val source = resolver.resolveAdHoc(dir.toPath())
        assertThat(source).isInstanceOf(InstallTemplateResolver.TemplateSource.Directory::class.java)
        assertThat((source as InstallTemplateResolver.TemplateSource.Directory).dir).isEqualTo(dir)
    }

    @Test
    fun `resolveAdHoc throws for non-existent path`() {
        val missing = File(tempDir, "does-not-exist").toPath()
        assertThatThrownBy { resolver.resolveAdHoc(missing) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ad-hoc path is not included in listAvailableTemplates`() {
        val templates = resolver.listAvailableTemplates()
        // --from is never in the list regardless of what directories exist
        assertThat(templates).doesNotContain("does-not-exist")
    }

    // ── listTemplateFiles ────────────────────────────────────────────────────

    @Test
    fun `listTemplateFiles includes both template and verbatim files for Directory source`() {
        val dir = File(tempDir, "adhoc").also { it.mkdirs() }
        File(dir, "start.sh.template").writeText("#!/bin/bash")
        File(dir, "values.yaml.template").writeText("replicas: 1")
        File(dir, "dashboard.json").writeText("{}")

        val source = InstallTemplateResolver.TemplateSource.Directory(dir)
        val files = resolver.listTemplateFiles(source)

        assertThat(files.map { it.name }).containsExactlyInAnyOrder(
            "start.sh.template",
            "values.yaml.template",
            "dashboard.json",
        )
    }

    @Test
    fun `listTemplateFiles excludes install yaml from Directory source`() {
        val dir = File(tempDir, "adhoc-no-yaml").also { it.mkdirs() }
        File(dir, "start.sh.template").writeText("#!/bin/bash")
        File(dir, Constants.Kit.CONFIG_FILE).writeText("name: mydb")

        val source = InstallTemplateResolver.TemplateSource.Directory(dir)
        val files = resolver.listTemplateFiles(source)

        assertThat(files.map { it.name }).containsExactlyInAnyOrder("start.sh.template")
    }

    @Test
    fun `listTemplateFiles preserves relative path for files in subdirectories`() {
        val dir = File(tempDir, "subdir-test").also { it.mkdirs() }
        File(dir, "bin").mkdirs()
        File(dir, "bin/start.sh.template").writeText("#!/bin/bash")
        File(dir, "values.yaml.template").writeText("replicas: 1")

        val source = InstallTemplateResolver.TemplateSource.Directory(dir)
        val files = resolver.listTemplateFiles(source)

        assertThat(files.map { it.name }).containsExactlyInAnyOrder("bin/start.sh.template", "values.yaml.template")
    }

    // ── loadInstallConfig ────────────────────────────────────────────────────

    @Test
    fun `loadInstallConfig returns null when install yaml is absent for Directory source`() {
        val dir = File(tempDir, "no-config").also { it.mkdirs() }
        val source = InstallTemplateResolver.TemplateSource.Directory(dir)
        assertThat(resolver.loadInstallConfig(source)).isNull()
    }

    @Test
    fun `loadInstallConfig parses install yaml from Directory source`() {
        val dir = File(tempDir, "with-config").also { it.mkdirs() }
        File(dir, Constants.Kit.CONFIG_FILE).writeText("name: mydb\ndescription: My DB")
        val source = InstallTemplateResolver.TemplateSource.Directory(dir)
        val config = requireNotNull(resolver.loadInstallConfig(source))
        assertThat(config.name).isEqualTo("mydb")
        assertThat(config.description).isEqualTo("My DB")
    }

    @Test
    fun `loadInstallConfig parses collision-check flag from Directory source`() {
        val profileDir = File(File(ctx.profileDir, "kits"), "mydb").also { it.mkdirs() }
        File(profileDir, Constants.Kit.CONFIG_FILE).writeText("name: mydb\ncollision-check: true")
        val source = InstallTemplateResolver.TemplateSource.Directory(profileDir)
        val config = requireNotNull(resolver.loadInstallConfig(source))
        assertThat(config.collisionCheck).isTrue()
    }

    @Test
    fun `listTemplateFiles preserves relative path for Directory source`() {
        val profileDir = File(File(ctx.profileDir, "kits"), "mydb").also { it.mkdirs() }
        File(profileDir, "bin").mkdirs()
        File(profileDir, "bin/start.sh.template").writeText("#!/bin/bash")
        File(profileDir, "README.md.template").writeText("# readme")

        val source = InstallTemplateResolver.TemplateSource.Directory(profileDir)
        val files = resolver.listTemplateFiles(source)

        assertThat(files.map { it.name }).containsExactlyInAnyOrder("bin/start.sh.template", "README.md.template")
    }

    @Test
    fun `listTemplateFiles returns real presto builtin templates from classpath`() {
        val source = resolver.resolve("presto")
        val files = resolver.listTemplateFiles(source)

        assertThat(files).isNotEmpty
        assertThat(files.map { it.name }).contains("values.yaml.template", "bin/start.sh.template")
        assertThat(files.all { it.content.isNotEmpty() }).isTrue()
    }

    @Test
    fun `listTemplateFiles includes dashboard json files for clickhouse builtin source`() {
        val source = resolver.resolve("clickhouse")
        val files = resolver.listTemplateFiles(source)

        assertThat(files.map { it.name }).contains(
            "dashboards/clickhouse.json",
            "dashboards/clickhouse-logs.json",
        )
    }

    @Test
    fun `listTemplateFiles excludes install yaml from builtin source`() {
        val source = resolver.resolve("clickhouse")
        val files = resolver.listTemplateFiles(source)

        assertThat(files.map { it.name }).doesNotContain(Constants.Kit.CONFIG_FILE)
    }

    @Test
    fun `loadInstallConfig returns real presto config from classpath`() {
        val source = resolver.resolve("presto")
        val config = resolver.loadInstallConfig(source)

        requireNotNull(config) { "expected presto ${Constants.Kit.CONFIG_FILE} to be present on classpath" }
        assertThat(config.name).isEqualTo("presto")
    }

    // ── additional sources ──────────────────────────────────────────────────

    private fun createAdditionalSource(vararg kitNames: String): File {
        val sourceDir = File(tempDir, "extra-source-${kitNames.first()}").also { it.mkdirs() }
        for (name in kitNames) {
            val kitDir = File(sourceDir, name).also { it.mkdirs() }
            File(kitDir, "start.sh.template").writeText("#!/bin/bash\necho $name")
        }
        return sourceDir
    }

    private fun resolverWithSource(sourceDir: File): InstallTemplateResolver {
        val provider = KitSourcesProvider(ctx)
        provider.add("test-source", sourceDir.absolutePath)
        return InstallTemplateResolver(ctx, provider)
    }

    @Test
    fun `kit from additional source appears in listAvailableTemplates`() {
        val sourceDir = createAdditionalSource("my-external-kit")
        val r = resolverWithSource(sourceDir)
        assertThat(r.listAvailableTemplates()).contains("my-external-kit")
    }

    @Test
    fun `additional source kit resolves to Directory source`() {
        val sourceDir = createAdditionalSource("my-external-kit")
        val r = resolverWithSource(sourceDir)
        val source = r.resolve("my-external-kit")
        assertThat(source).isInstanceOf(InstallTemplateResolver.TemplateSource.Directory::class.java)
        assertThat((source as InstallTemplateResolver.TemplateSource.Directory).dir)
            .isEqualTo(File(sourceDir, "my-external-kit").canonicalFile)
    }

    @Test
    fun `additional source kit shadows built-in of same name`() {
        val sourceDir = createAdditionalSource("clickhouse")
        val r = resolverWithSource(sourceDir)
        val source = r.resolve("clickhouse")
        assertThat(source).isInstanceOf(InstallTemplateResolver.TemplateSource.Directory::class.java)
        assertThat((source as InstallTemplateResolver.TemplateSource.Directory).dir)
            .isEqualTo(File(sourceDir, "clickhouse").canonicalFile)
    }

    @Test
    fun `profile kit shadows additional source of same name`() {
        val sourceDir = createAdditionalSource("mydb")
        val profileDir = createProfileTemplate("mydb")
        val r = resolverWithSource(sourceDir)
        val source = r.resolve("mydb")
        assertThat(source).isInstanceOf(InstallTemplateResolver.TemplateSource.Directory::class.java)
        assertThat((source as InstallTemplateResolver.TemplateSource.Directory).dir).isEqualTo(profileDir)
    }

    @Test
    fun `missing additional source directory is silently skipped in list`() {
        val provider = KitSourcesProvider(ctx)
        provider.add("ghost", "/nonexistent/path/that/does/not/exist")
        val r = InstallTemplateResolver(ctx, provider)
        // Should not throw; built-ins still appear
        val templates = r.listAvailableTemplates()
        assertThat(templates).contains("clickhouse", "presto")
    }

    @Test
    fun `missing additional source directory is silently skipped in resolve`() {
        val provider = KitSourcesProvider(ctx)
        provider.add("ghost", "/nonexistent/path/that/does/not/exist")
        val r = InstallTemplateResolver(ctx, provider)
        // clickhouse should still resolve to its built-in
        val source = r.resolve("clickhouse")
        assertThat(source).isInstanceOf(InstallTemplateResolver.TemplateSource.Builtin::class.java)
    }
}
