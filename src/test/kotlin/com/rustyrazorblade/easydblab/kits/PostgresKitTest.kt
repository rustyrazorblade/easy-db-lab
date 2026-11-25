package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.ExtensionRegistry
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

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

    /** The kinds `stop` deletes by name, lowercased, with the group stripped. */
    private fun stopDeletedKinds(): List<String> =
        kit.config.stop
            .filterIsInstance<InstallStep.Delete>()
            .map { it.kind.substringBefore('.').lowercase() }

    /**
     * Deleting the CNPG Cluster on stop lost the dataset: CNPG bootstraps a Cluster it has not
     * seen with initdb, which moves the rebound PGDATA aside. Stop keeps the Cluster and what it
     * references, and `start` applies all three again.
     */
    @Test
    fun `stop keeps the Cluster, its Secret and its ImageCatalog, which start applies again`() {
        val applied =
            kit
                .render("postgres-cluster.yaml.template", POSTGRES_INSTALL_ARGS)
                .map { it.kind.lowercase() }

        assertThat(
            kit.config.start
                .filterIsInstance<InstallStep.Manifest>()
                .map { it.template },
        ).contains("postgres-cluster.yaml")
        assertThat(applied).contains("cluster", "secret", "imagecatalog")
        assertThat(stopDeletedKinds()).doesNotContainAnyElementsOf(applied)
        assertThat(stopDeletedKinds()).containsExactly("service")
    }

    @Test
    fun `start applies the Cluster awake, so a hibernated Cluster resumes`() {
        val cluster =
            kit
                .render("postgres-cluster.yaml.template", POSTGRES_INSTALL_ARGS)
                .single { it.kind == "Cluster" }

        assertThat(cluster.metadata.annotations).containsEntry(HIBERNATION, "off")
    }

    @Test
    fun `stop hibernates the instance's Cluster when it exists`() {
        val calls = runStopShellAgainstStub(clusterExists = true)

        assertThat(calls).anySatisfy {
            assertThat(it).contains("annotate", "cluster.postgresql.cnpg.io", "postgres-duckdb", "--overwrite", "$HIBERNATION=on")
        }
    }

    @Test
    fun `stop does nothing to a Cluster that is not there`() {
        val calls = runStopShellAgainstStub(clusterExists = false)

        assertThat(calls).isNotEmpty()
        assertThat(calls).noneSatisfy { assertThat(it).contains("annotate") }
    }

    /**
     * Uninstall is the one phase that deletes the dataset, now that stop keeps it: the Cluster
     * first (so CNPG stops writing), then its claims, then what it referenced, then the volumes.
     */
    @Test
    fun `uninstall deletes the Cluster, then its claims, Secret and ImageCatalog, then the volumes`() {
        val steps = kit.config.uninstall
        val deletes = steps.filterIsInstance<InstallStep.Delete>()
        val claims = deletes.single { it.bySelector }

        assertThat(deletes.filterNot { it.bySelector }.map { it.kind.substringBefore('.').lowercase() to it.name })
            .containsExactly(
                "cluster" to KIT_NAME_PLACEHOLDER,
                "secret" to "$KIT_NAME_PLACEHOLDER-credentials",
                "imagecatalog" to "$KIT_NAME_PLACEHOLDER-catalog",
            )
        assertThat(claims.kinds).containsExactly("pvc")
        assertThat(parseLabelSelector(claims.selector)).isEqualTo(mapOf("cnpg.io/cluster" to KIT_NAME_PLACEHOLDER))
        assertThat(steps.indexOf(claims)).isEqualTo(1)
        assertThat(steps.indexOfFirst { it is InstallStep.PlatformPvsDelete }).isGreaterThan(steps.indexOf(deletes.last()))
        assertThat(steps.last()).isInstanceOf(InstallStep.HelmUninstall::class.java)
    }

    /**
     * Runs every `stop` shell step with `KIT_NAME=postgres-duckdb` and `kubectl` as a recording
     * shell function, whose `get` prints the Cluster's name only when [clusterExists].
     */
    private fun runStopShellAgainstStub(clusterExists: Boolean): List<String> {
        val log = File(tempDir, "kubectl.log")
        val found = if (clusterExists) "echo cluster.postgresql.cnpg.io/postgres-duckdb" else ":"
        val stub =
            """
            kubectl() {
              echo "$*" >> "${log.absolutePath}"
              case "$1" in get) $found ;; esac
            }
            """.trimIndent()
        kit.config.stop.filterIsInstance<InstallStep.Shell>().forEach { step ->
            val process =
                ProcessBuilder("bash", "-c", stub + "\n" + step.script)
                    .apply { environment()["KIT_NAME"] = "postgres-duckdb" }
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "stop step hung: $output" }
            check(process.exitValue() == 0) { "stop step failed (${process.exitValue()}): $output" }
        }
        return if (log.exists()) log.readLines() else emptyList()
    }

    private companion object {
        const val KIT_NAME_PLACEHOLDER = "\${KIT_NAME}"
        const val HIBERNATION = "cnpg.io/hibernation"
        const val SCRIPT_TIMEOUT_SECONDS = 20L

        /** What `kit install postgres` resolves at install time for the default image. */
        val POSTGRES_INSTALL_ARGS =
            mapOf(
                "PG_MAJOR_VERSION" to "17",
                "IMAGE" to "ghcr.io/cloudnative-pg/postgresql:17",
                "POSTGRES_UID" to "26",
                "POSTGRES_GID" to "26",
                "SHARED_PRELOAD_LIBRARIES" to "''",
                "POST_INIT_SQL" to "[]",
            )

        /** A kubectl `-l <selector>` flag, optionally quoted. */
        val LABEL_FLAG = Regex("""-l\s+["']?([^\s"']+)""")
    }
}
