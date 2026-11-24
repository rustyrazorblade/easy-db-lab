package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.ExtensionRegistry
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
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
        BuiltinKitFixture("postgres", TemplateService(ClusterStateManager(File(tempDir, "state.json"))))
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
     * CNPG bootstraps a Cluster it has never seen: a Cluster applied again after `stop` deleted it
     * ran `bootstrap.initdb` on the rebound volume, which moved the old PGDATA aside and served a
     * new empty database. `stop` therefore hibernates the Cluster (`cnpg.io/hibernation=on`: CNPG
     * removes the pods and keeps the Cluster, its status and its claims) and never deletes it or the
     * objects it references.
     */
    @Test
    fun `stop hibernates the Cluster and never deletes it, its claims, credentials or image catalog`() {
        val deleted =
            kit.config.stop
                .filterIsInstance<InstallStep.Delete>()
                .flatMap { it.kinds + it.kind }
                .map { it.lowercase() }
        val stub = StubKubectl(File(tempDir, "stub"))
        stub.respond("get", "$CNPG_CLUSTER/postgres\n")

        val exit = stub.run(stopScript(), mapOf("KIT_NAME" to "postgres"))

        assertThat(exit).withFailMessage(stub.output()).isEqualTo(0)
        assertThat(deleted).doesNotContain("cluster", "secret", "imagecatalog", "pvc", "persistentvolumeclaim")
        assertThat(stub.invocations()).anySatisfy { call ->
            assertThat(call).startsWith("annotate $CNPG_CLUSTER postgres")
            assertThat(call).contains("-n default", "--overwrite", "$HIBERNATION=on")
        }
    }

    /** A stop of an instance that never started (no Cluster) succeeds and annotates nothing. */
    @Test
    fun `stop of an instance with no Cluster succeeds without annotating`() {
        val stub = StubKubectl(File(tempDir, "stub"))
        stub.respond("get", "")

        val exit = stub.run(stopScript(), mapOf("KIT_NAME" to "postgres"))

        assertThat(exit).withFailMessage(stub.output()).isEqualTo(0)
        assertThat(stub.invocations()).noneSatisfy { assertThat(it).startsWith("annotate") }
    }

    /**
     * `start` applies the Cluster with hibernation off, so a Cluster `stop` hibernated is woken on
     * its own volumes and status, with no new bootstrap.
     */
    @Test
    fun `start applies the Cluster with hibernation off`() {
        val cluster =
            kit
                .render("postgres-cluster.yaml.template", POSTGRES_INSTALL_VARS)
                .filterIsInstance<GenericKubernetesResource>()
                .single { it.kind == "Cluster" }

        assertThat(cluster.metadata.annotations).containsEntry(HIBERNATION, "off")
    }

    /**
     * The claims outlive `stop`, so the instance keeps them bound; `uninstall` is the one phase that
     * deletes the Cluster and its claims, and does so before releasing the storage.
     */
    @Test
    fun `uninstall deletes the Cluster and its claims before releasing the db storage`() {
        val uninstall = kit.config.uninstall
        val cluster = uninstall.indexOfFirst { it is InstallStep.Delete && it.kind == CNPG_CLUSTER && it.name == KIT_NAME_PLACEHOLDER }
        val claims =
            uninstall.indexOfFirst {
                it is InstallStep.Delete && "pvc" in it.kinds && it.selector == "cnpg.io/cluster=$KIT_NAME_PLACEHOLDER"
            }
        val release = uninstall.indexOf(InstallStep.KitStorageDelete(nodeType = "db"))

        assertThat(cluster).isNotNegative()
        assertThat(claims).isGreaterThan(cluster)
        assertThat(release).isGreaterThan(claims)
        assertThat(
            kit.config.start
                .filterIsInstance<InstallStep.KitStorage>()
                .single()
                .rebindReleased,
        ).isFalse()
    }

    private fun stopScript(): String =
        kit.config.stop
            .filterIsInstance<InstallStep.Shell>()
            .single()
            .script

    /** Uninstall removes the instance's local PVs, its `edl-local-<instance>` class and directories. */
    @Test
    fun `uninstall releases the instance's db storage`() {
        assertThat(kit.config.uninstall).contains(InstallStep.KitStorageDelete(nodeType = "db"))
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

        /** CNPG's declarative hibernation annotation. */
        const val HIBERNATION = "cnpg.io/hibernation"

        /** The CNPG Cluster resource, fully qualified so no other `cluster` kind can match. */
        const val CNPG_CLUSTER = "cluster.postgresql.cnpg.io"

        /** Values `kit install` derives for a postgres instance rather than reads from arg defaults. */
        val POSTGRES_INSTALL_VARS =
            mapOf(
                "IMAGE" to "ghcr.io/cloudnative-pg/postgresql:17",
                "PG_MAJOR_VERSION" to "17",
                "SHARED_PRELOAD_LIBRARIES" to "[]",
                "POST_INIT_SQL" to "[]",
                "POSTGRES_UID" to "26",
                "POSTGRES_GID" to "26",
            )

        /** A kubectl `-l <selector>` flag, optionally quoted. */
        val LABEL_FLAG = Regex("""-l\s+["']?([^\s"']+)""")
    }
}
