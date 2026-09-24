package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.ExtensionRegistry
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Checks the built-in postgres kit's instance scoping. `postgres` and `postgres-<extension>` run
 * side by side, each a CNPG Cluster named after the kit instance, so every pod selector the kit
 * uses must name its own Cluster — otherwise one instance's `start` waits on, or is satisfied by,
 * the other instance's pods.
 */
class PostgresKitTest : BaseKoinTest() {
    private val kit by lazy {
        BuiltinKitFixture("postgres", TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))
    }

    private fun shellSelectors(steps: List<InstallStep>): List<Map<String, String>> =
        steps
            .filterIsInstance<InstallStep.Shell>()
            .flatMap { step -> LABEL_FLAG.findAll(step.script).map { it.groupValues[1] } }
            .map { parseLabelSelector(it) }

    @Test
    fun `every start wait selects only this instance's running pods`() {
        val selectors = shellSelectors(kit.config.start)

        assertThat(selectors).isNotEmpty()
        assertThat(selectors).allSatisfy { selector ->
            assertThat(selector).containsEntry("cnpg.io/cluster", KIT_NAME_PLACEHOLDER)
            assertThat(selector).containsEntry("cnpg.io/podRole", "instance")
        }
    }

    @Test
    fun `start waits select the same pods as the kit's runtime`() {
        val runtime = parseLabelSelector(requireNotNull(kit.config.runtime).selector)

        assertThat(shellSelectors(kit.config.start)).allSatisfy { assertThat(it).isEqualTo(runtime) }
    }

    /**
     * postgres and postgres-duckdb both reported job="postgres", told apart only by instance. A
     * scrape with no fixed job is named after the kit instance, as every other kit's is.
     */
    @Test
    fun `the scrape job is named after the kit instance`() {
        assertThat(kit.scrapeMetrics.single().job).isBlank()
    }

    /**
     * Plain postgres and every postgres-<extension> instance share one CNPG operator release, so
     * uninstalling one instance keeps the operator while any CNPG Cluster is left.
     */
    @Test
    fun `uninstall keeps the CNPG operator while any CNPG Cluster is left`() {
        val operator =
            kit.config.uninstall
                .filterIsInstance<InstallStep.HelmUninstall>()
                .single()
        val installed =
            kit.config.install
                .filterIsInstance<InstallStep.Helm>()
                .single()

        assertThat(operator.release).isEqualTo(installed.release)
        assertThat(operator.keepWhileAny).isEqualTo("clusters.postgresql.cnpg.io")
    }

    /**
     * Each postgres instance installs the overview plus its own extension's dashboard. A declared
     * `dashboards` list replaces the scan of `dashboards/`, so a dashboard file left undeclared
     * would never be installed.
     */
    @Test
    fun `every dashboard is declared, the overview for all instances and each other for its extension`() {
        val dir = requireNotNull(javaClass.classLoader.getResource("com/rustyrazorblade/easydblab/kits/postgres/dashboards"))
        val files = File(dir.toURI()).listFiles { _, name -> name.endsWith(".json") }.orEmpty().map { "dashboards/${it.name}" }
        val declared = kit.config.dashboards

        assertThat(declared.map { it.path }).containsExactlyInAnyOrderElementsOf(files)
        assertThat(declared.filter { it.extension.isBlank() }.map { it.path }).containsExactly("dashboards/postgres.json")
        assertThat(declared.map { it.extension }.filter { it.isNotBlank() })
            .containsExactlyInAnyOrderElementsOf(ExtensionRegistry.fromClasspath("postgres").all().keys)
    }

    private companion object {
        const val KIT_NAME_PLACEHOLDER = "\${KIT_NAME}"

        /** A kubectl `-l <selector>` flag, optionally quoted. */
        val LABEL_FLAG = Regex("""-l\s+["']?([^\s"']+)""")
    }
}
