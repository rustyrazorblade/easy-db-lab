package com.rustyrazorblade.easydblab.commands.kit

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitCapability
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Tests for KitInfo command — verifies that kit metadata is correctly read
 * from built-in kit.yaml files and formatted into user-readable output.
 *
 * Calls buildInfoText() and buildCommandList() directly rather than capturing
 * stdout, since KitInfo is a read-only display command that uses println() with
 * no associated events.
 *
 * Behavior tests (command listing, ordering, annotations) use synthetic KitConfig
 * data so they are not coupled to the contents of any specific kit.
 */
class KitInfoTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { TemplateService(get(), get()) }
                single { InstallTemplateResolver(get()) }
            },
        )

    private fun buildInfo(kitName: String): String {
        val resolver = getKoin().get<InstallTemplateResolver>()
        val source = resolver.resolve(kitName)
        val config = resolver.loadInstallConfig(source) ?: error("No kit.yaml for $kitName")
        val templateFiles = resolver.listTemplateFiles(source).map { it.name }
        return KitInfo.buildInfoText(config, templateFiles)
    }

    // ── Kit metadata (reads real kit files, no command-list assertions) ──────

    @Test
    fun `clickhouse info includes name version and type`() {
        val output = buildInfo("clickhouse")
        assertThat(output).contains("clickhouse")
        assertThat(output).contains("1.0.0")
        assertThat(output).contains("[db]")
    }

    @Test
    fun `clickhouse info lists configurable args with flags and defaults`() {
        val output = buildInfo("clickhouse")
        assertThat(output).contains("Args:")
        assertThat(output).contains("--clickhouse-version")
        assertThat(output).contains("--size")
        assertThat(output).contains("--replicas")
        assertThat(output).contains("(default:")
    }

    @Test
    fun `clickhouse info lists exposed endpoints`() {
        val output = buildInfo("clickhouse")
        assertThat(output).contains("Endpoints:")
        assertThat(output).contains("HTTP")
        assertThat(output).contains(":30123")
        assertThat(output).contains("Native")
        assertThat(output).contains(":30900")
    }

    @Test
    fun `presto info shows type and description`() {
        val output = buildInfo("presto")
        assertThat(output).contains("presto")
        assertThat(output).contains("[app]")
        assertThat(output).contains("Presto")
    }

    @Test
    fun `presto info shows post-workload hooks`() {
        val output = buildInfo("presto")
        assertThat(output).contains("Hooks:")
        assertThat(output).contains("post-workload-start")
        assertThat(output).contains("bin/update-catalogs.sh")
    }

    @Test
    fun `unknown kit name throws IllegalArgumentException`() {
        val resolver = getKoin().get<InstallTemplateResolver>()
        assertThatThrownBy { resolver.resolve("no-such-kit") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no-such-kit")
    }

    // ── Command list behaviour (synthetic data, not tied to any specific kit) ─

    private val syntheticConfig = KitConfig(name = "mykit")
    private val syntheticScripts = setOf("start", "stop", "backup")

    @Test
    fun `commands section uses kit-prefixed invocations`() {
        val output = KitInfo.buildInfoText(syntheticConfig, listOf("bin/start.sh.template"))
        assertThat(output).contains("Commands:")
        assertThat(output).contains("mykit start")
    }

    @Test
    fun `status is always included in command list`() {
        // status has no bin script — it is provided by the kit runner for every installed kit
        val commands = KitInfo.buildCommandList(syntheticConfig, emptySet(), emptyList())
        assertThat(commands.map { it.first }).contains("status")
    }

    @Test
    fun `buildCommandList returns commands in alphabetical order`() {
        val commands = KitInfo.buildCommandList(syntheticConfig, syntheticScripts, listOf("sql" to "Execute SQL"))
        val names = commands.map { it.first }
        assertThat(names).isSortedAccordingTo(compareBy { it })
    }

    @Test
    fun `annotated commands appear in output with their description`() {
        val output =
            KitInfo.buildInfoText(
                syntheticConfig,
                emptyList(),
                listOf("sql" to "Execute SQL against the cluster"),
            )
        assertThat(output).contains("mykit sql")
        assertThat(output).contains("Execute SQL against the cluster")
    }

    @Test
    fun `annotated commands are absent when list is empty`() {
        val commands = KitInfo.buildCommandList(syntheticConfig, syntheticScripts, emptyList())
        assertThat(commands.map { it.first }).doesNotContain("sql")
    }

    @Test
    fun `known phase descriptions appear for lifecycle commands`() {
        val output = KitInfo.buildInfoText(syntheticConfig, listOf("bin/start.sh.template", "bin/stop.sh.template"))
        assertThat(output).contains("Start the workload")
        assertThat(output).contains("Stop the workload")
    }

    @Test
    fun `capability commands appear in command list`() {
        val config = KitConfig(name = "mykit", capabilities = listOf(KitCapability(type = "sql")))
        val commands = KitInfo.buildCommandList(config, emptySet(), emptyList())
        val entry = commands.find { it.first == "sql" }
        assertThat(entry).isNotNull
        assertThat(entry!!.second).isEqualTo("Execute SQL against mykit")
    }

    @Test
    fun `capability commands absent when capabilities list is empty`() {
        val commands = KitInfo.buildCommandList(syntheticConfig, syntheticScripts, emptyList())
        assertThat(commands.map { it.first }).doesNotContain("sql")
    }

    @Test
    fun `unknown script commands fall back to (script) label`() {
        val commands = KitInfo.buildCommandList(syntheticConfig, setOf("custom-export"), emptyList())
        val customEntry = commands.find { it.first == "custom-export" }
        assertThat(customEntry).isNotNull
        assertThat(customEntry!!.second).isEqualTo("(script)")
    }
}
