package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.interpolateStepVars
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import java.io.File

/**
 * The built-in kits install into the cluster namespace (issue 1094, design D4 and D7). A cluster
 * `init` recorded with namespace `lab-a` gets every namespaced object of every kit manifest in
 * `lab-a`, Strimzi watches `lab-a`, and the only operator namespaces are the kits' own, never a
 * system namespace.
 */
class KitNamespaceTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { TemplateService(get(), get()) }
                single { KitSourcesProvider(get()) }
                single { InstallTemplateResolver(get(), get()) }
            },
        )

    private fun kits(): List<KitConfig> {
        val resolver = get<InstallTemplateResolver>()
        return resolver.listAvailableTemplates().mapNotNull { resolver.loadInstallConfig(resolver.resolve(it)) }
    }

    private fun fixture(kit: String) =
        BuiltinKitFixture(kit, TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()), namespace = LAB_A)

    private fun manifestTemplates(kit: String): List<String> {
        val resolver = get<InstallTemplateResolver>()
        val fixture = fixture(kit)
        return resolver
            .listTemplateFiles(resolver.resolve(kit))
            .map { it.name }
            .filter { it.endsWith(".yaml.template") && "/" !in it && MANIFEST_KIND.containsMatchIn(fixture.resource(it)) }
    }

    @Test
    fun `every namespaced object a kit manifest renders lands in the cluster namespace`() {
        val elsewhere =
            kits().flatMap { kit ->
                val fixture = fixture(kit.name)
                manifestTemplates(kit.name).flatMap { file ->
                    fixture
                        .render(file, INSTALL_TIME_VARS)
                        .filter { it.metadata.namespace != LAB_A }
                        .map { "${kit.name}/$file: ${it.kind}/${it.metadata.name} in ${it.metadata.namespace}" }
                }
            }

        assertThat(kits().map { it.name }).contains("kafka", "postgres", "cassandra-pods")
        assertThat(elsewhere).isEmpty()
    }

    @Test
    fun `Strimzi watches the cluster namespace`() {
        val kafka = fixture("kafka")
        val operator =
            kafka.config.install
                .filterIsInstance<InstallStep.Helm>()
                .single { it.release == "strimzi-operator" }

        assertThat(interpolateStepVars(operator.values.getValue("watchNamespaces"), kafka.stepVariables)).isEqualTo("{$LAB_A}")
    }

    @Test
    fun `operators run in their kits' own namespaces, each created before its chart`() {
        val operators =
            kits().flatMap { kit ->
                kit.install.filterIsInstance<InstallStep.Helm>().map { helm ->
                    val created =
                        kit.install
                            .take(kit.install.indexOf(helm))
                            .filterIsInstance<InstallStep.Namespace>()
                            .map { it.name }
                    Triple(kit.name, helm.namespace, helm.namespace in created)
                }
            }

        assertThat(operators.map { it.second }).doesNotContain("kube-system", "kube-public", "default")
        assertThat(operators.filter { it.second != null && !it.third }.map { "${it.first}: ${it.second}" }).isEmpty()
        assertThat(operators.single { it.first == "clickhouse" }.second).isEqualTo("clickhouse-operator")
    }

    /** Presto and Trino reach the database kits they inject catalogs for through their Services in the cluster namespace. */
    @Test
    fun `injected catalogs point at the database Services in the cluster namespace`() {
        val catalogs =
            CATALOG_URLS.map { (kitAndFile, key) ->
                val (kit, file) = kitAndFile
                val properties = fixture(kit).renderText("catalogs/$file")
                assertThat(properties).doesNotContain(".default.svc")
                "$kit/$file" to properties.lineSequence().single { it.startsWith("$key=") }.substringAfter("=")
            }

        assertThat(catalogs).containsExactly(
            "presto/postgres.properties.template" to "jdbc:postgresql://postgres-rw.$LAB_A.svc:5432/postgres",
            "presto/clickhouse.properties.template" to "jdbc:clickhouse://clickhouse-clickhouse.$LAB_A.svc:8123/default",
            "trino/clickhouse.properties.template" to "jdbc:clickhouse://clickhouse-clickhouse.$LAB_A.svc:8123/default",
            "trino/tidb.properties.template" to
                "jdbc:mysql://tidb-nodeport.$LAB_A.svc:4000/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        )
    }

    private companion object {
        const val LAB_A = "lab-a"

        /** Each injected catalog template, keyed by kit and file, with the property that names its database. */
        val CATALOG_URLS =
            listOf(
                ("presto" to "postgres.properties.template") to "connection-url",
                ("presto" to "clickhouse.properties.template") to "clickhouse.connection-url",
                ("trino" to "clickhouse.properties.template") to "connection-url",
                ("trino" to "tidb.properties.template") to "connection-url",
            )
        val MANIFEST_KIND = Regex("""(?m)^kind:""")

        /** The install-time args some templates need and no kit default supplies (see KitClaimStorageTest). */
        val INSTALL_TIME_VARS =
            mapOf(
                "IMAGE" to "ghcr.io/cloudnative-pg/postgresql:17",
                "PG_MAJOR_VERSION" to "17",
                "POSTGRES_UID" to "26",
                "POSTGRES_GID" to "26",
                "SHARED_PRELOAD_LIBRARIES" to "[]",
                "POST_INIT_SQL" to "[]",
                "POSTGRES_PORT" to "30432",
                "METRICS_PORT" to "30987",
                "CASSANDRA_NODES" to "3",
            )
    }
}
