package com.rustyrazorblade.easydblab.commands.kit

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File

/**
 * Guards the sysbench kit's user-guide Flags table against drift from its kit.yaml.
 *
 * `--rate`, `--skip-trx`, and `--rand-type` were real kit.yaml args for a long time while
 * being absent from the docs table (issue #839), so a user reading the guide had no way to
 * discover them. This test fails when a flag is added to kit.yaml without a matching row.
 *
 * Asserts flag presence only — never defaults or description wording, so rewording the docs
 * does not break the build.
 */
class SysbenchFlagsDocumentedTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { TemplateService(get(), get()) }
                single { KitSourcesProvider(get()) }
                single { InstallTemplateResolver(get(), get()) }
            },
        )

    private fun declaredFlags(): List<String> {
        val resolver = getKoin().get<InstallTemplateResolver>()
        val source = resolver.resolve(KIT_NAME)
        val config = resolver.loadInstallConfig(source) ?: error("No kit.yaml for $KIT_NAME")
        return (config.args + config.commands.values.flatMap { it.args })
            .map { it.flag }
            .distinct()
    }

    /**
     * Pulls the flags out of the `## Flags` table only — a flag mentioned elsewhere in the
     * prose is not the same as one a reader can find in the table.
     */
    private fun documentedFlags(): List<String> {
        val docs = File(DOCS_PATH)
        assertThat(docs)
            .describedAs("sysbench user guide, resolved from working dir ${File(".").absolutePath}")
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

    @Test
    fun `every sysbench kit flag appears in the user guide Flags table`() {
        val declared = declaredFlags()
        val documented = documentedFlags()

        assertThat(declared).isNotEmpty()
        assertThat(documented)
            .describedAs("flags in the Flags table of $DOCS_PATH")
            .containsAll(declared)
    }

    companion object {
        private const val KIT_NAME = "sysbench"
        private const val DOCS_PATH = "docs/user-guide/sysbench.md"

        /** Matches the leading `| `--flag` |` cell of a Flags-table row. */
        private val TABLE_FLAG = Regex("""^\|\s*`(--[a-z0-9-]+)`\s*\|""")
    }
}
