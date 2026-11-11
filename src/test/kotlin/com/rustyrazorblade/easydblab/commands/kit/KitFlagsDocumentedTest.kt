package com.rustyrazorblade.easydblab.commands.kit

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File

/**
 * Guards each benchmark kit's user-guide Flags table against drift from its kit.yaml.
 *
 * `--rate`, `--skip-trx`, and `--rand-type` were real sysbench kit.yaml args for a long time while
 * being absent from the docs table (issue #839), so a user reading the guide had no way to
 * discover them. This test fails when a flag is added to a kit's kit.yaml without a matching row
 * in `docs/user-guide/<kit>.md`.
 *
 * Asserts flag presence only — never defaults or description wording, so rewording the docs
 * does not break the build.
 */
class KitFlagsDocumentedTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { TemplateService(get(), get()) }
                single { KitSourcesProvider(get()) }
                single { InstallTemplateResolver(get(), get()) }
            },
        )

    private fun declaredFlags(kitName: String): List<String> {
        val resolver = getKoin().get<InstallTemplateResolver>()
        val source = resolver.resolve(kitName)
        val config = resolver.loadInstallConfig(source) ?: error("No kit.yaml for $kitName")
        return (config.args + config.commands.values.flatMap { it.args })
            .map { it.flag }
            .distinct()
    }

    /**
     * Pulls the flags out of the `## Flags` table only — a flag mentioned elsewhere in the
     * prose is not the same as one a reader can find in the table.
     */
    private fun documentedFlags(docsPath: String): List<String> {
        val docs = File(docsPath)
        assertThat(docs)
            .describedAs("user guide, resolved from working dir ${File(".").absolutePath}")
            .exists()

        val flagsSection =
            docs
                .readLines()
                .dropWhile { it.trim() != "## Flags" }
                .drop(1)
                .takeWhile { !it.startsWith("## ") }

        return flagsSection.mapNotNull { line ->
            TABLE_FLAG.find(line)?.groupValues?.get(1)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["sysbench", "clickhouse-benchmark"])
    fun `every kit flag appears in the user guide Flags table`(kitName: String) {
        val docsPath = "docs/user-guide/$kitName.md"
        val declared = declaredFlags(kitName)
        val documented = documentedFlags(docsPath)

        assertThat(declared).isNotEmpty()
        assertThat(documented)
            .describedAs("flags in the Flags table of $docsPath")
            .containsAll(declared)
    }

    companion object {
        /** Matches the leading `| `--flag` |` cell of a Flags-table row. */
        private val TABLE_FLAG = Regex("""^\|\s*`(--[a-z0-9-]+)`\s*\|""")
    }
}
