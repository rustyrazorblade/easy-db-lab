package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardDefaults
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardTreeWriter
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.services.DashboardRef
import com.rustyrazorblade.easydblab.services.KitConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The dashboard trees a local stack provisions: the packaged core and kit dashboards, or any
 * directory of `<folder>/<file>.json` given with `--from`. Every file goes through the install-time
 * pass for the live local stack (no current cluster, no current experiment).
 */
class LocalDashboardTreeTest {
    @TempDir
    lateinit var root: File

    private val context =
        DashboardInstallContext(
            cluster = null,
            experiment = null,
            tenants = TenantSet.of("acme", listOf("default")),
            documentsUrl = "http://127.0.0.1:3380",
        )

    private val homeTenantDashboard =
        """{"uid":"%s","title":"t","links":[{"url":"__DOCUMENTS_URL__/reports/x"}],
           |"templating":{"list":[{"name":"home_tenant","type":"custom","query":""}]}}
        """.trimMargin()

    private fun dashboard(uid: String) = homeTenantDashboard.format(uid)

    private fun homeTenantOf(json: String): String =
        Json
            .parseToJsonElement(json)
            .jsonObject
            .getValue("templating")
            .jsonObject
            .getValue("list")
            .jsonArray
            .single()
            .jsonObject
            .getValue("query")
            .jsonPrimitive.content

    private fun uidOf(json: String): String =
        Json
            .parseToJsonElement(json)
            .jsonObject
            .getValue("uid")
            .jsonPrimitive.content

    @Test
    fun `a directory tree is every folder's dashboards after the install-time pass`() {
        File(root, "work/a.json").apply { parentFile.mkdirs() }.writeText(dashboard("a"))
        File(root, "README.md").writeText("not a dashboard")

        val tree = DirectoryDashboardTree(root).render(context)

        assertThat(tree.keys).containsExactly("work/a.json")
        assertThat(homeTenantOf(tree.getValue("work/a.json"))).isEqualTo("acme")
        assertThat(tree.getValue("work/a.json")).contains("http://127.0.0.1:3380/reports/x").doesNotContain("__DOCUMENTS_URL__")
    }

    @Test
    fun `a directory dashboard outside a folder or nested deeper is refused and named`() {
        File(root, "loose.json").writeText(dashboard("a"))
        File(root, "f/g/deep.json").apply { parentFile.mkdirs() }.writeText(dashboard("b"))

        assertThatThrownBy { DirectoryDashboardTree(root).render(context) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("loose.json")
            .hasMessageContaining("f/g/deep.json")
    }

    @Test
    fun `a directory that does not exist is refused and named`() {
        val missing = File(root, "nope")

        assertThatThrownBy { DirectoryDashboardTree(missing).render(context) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(missing.path)
    }

    @Test
    fun `a kit installs its plain dashboards in its own folder and each extension's in that instance's folder`() {
        val kit =
            PackagedKitDashboards(
                config =
                    KitConfig(
                        name = "pg",
                        dashboards = listOf(DashboardRef("dashboards/pg.json"), DashboardRef("dashboards/ext.json", extension = "ext")),
                    ),
                files = mapOf("dashboards/pg.json" to dashboard("pg-overview"), "dashboards/ext.json" to dashboard("pg-ext")),
            )

        val instances = kit.instances()

        assertThat(instances.keys).containsExactlyInAnyOrder("pg/pg.json", "pg-ext/pg.json", "pg-ext/ext.json")
        assertThat(uidOf(instances.getValue("pg/pg.json"))).isEqualTo("pg-overview")
        assertThat(uidOf(instances.getValue("pg-ext/pg.json"))).isEqualTo("pg-overview-ext")
        assertThat(uidOf(instances.getValue("pg-ext/ext.json"))).isEqualTo("pg-ext-ext")
    }

    @Test
    fun `a kit that declares no dashboards installs every file in its dashboards directory`() {
        val kit =
            PackagedKitDashboards(
                config = KitConfig(name = "kv"),
                files = mapOf("dashboards/b.json" to dashboard("b"), "dashboards/a.json" to dashboard("a")),
            )

        assertThat(kit.instances().keys).containsExactly("kv/a.json", "kv/b.json")
    }

    @Test
    fun `the packaged tree is the core catalog and every packaged kit, each after the install-time pass`() {
        val catalog = GrafanaDashboardCatalog.discover()
        val kits = PackagedKitDashboards.discover()

        val tree = PackagedDashboardTree(catalog, kits).render(context)

        assertThat(tree.keys).contains(Constants.Grafana.HOME_DASHBOARD_PATH)
        assertThat(tree.keys).containsAll(catalog.dashboards.map { it.relativePath })
        assertThat(kits).isNotEmpty
        assertThat(tree.keys.map { it.substringBefore('/') }).containsAll(kits.map { it.config.name })
        assertThat(tree.values).noneMatch {
            DashboardDefaults.DOCUMENTS_URL_PLACEHOLDER in it || GrafanaDashboardTreeWriter.PYROSCOPE_URL_PLACEHOLDER in it
        }
    }

    @Test
    fun `no two packaged dashboards share a uid, or Grafana would keep only one of them`() {
        val tree = PackagedDashboardTree(GrafanaDashboardCatalog.discover(), PackagedKitDashboards.discover()).render(context)

        val uids =
            tree.mapValues { (_, json) ->
                Json
                    .parseToJsonElement(json)
                    .jsonObject["uid"]
                    ?.jsonPrimitive
                    ?.content
            }
        val shared =
            uids.entries
                .filter { it.value != null }
                .groupBy({ it.value }, { it.key })
                .filterValues { it.size > 1 }
        assertThat(shared).isEmpty()
    }
}
