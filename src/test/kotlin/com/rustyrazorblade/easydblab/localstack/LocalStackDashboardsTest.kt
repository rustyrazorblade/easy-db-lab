package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

/**
 * A reload that Grafana answers is not enough: right after the tree is renamed into place, the
 * container can still see the old, removed directory, and Grafana's walk of it loads nothing and
 * answers 200. An install with a reload returns only once Grafana serves every dashboard of the tree
 * that has a uid, reloading again until it does, and fails naming the missing ones when it never does.
 * Grafana is a fake at its HTTP boundary.
 */
class LocalStackDashboardsTest {
    @TempDir
    lateinit var stack: File

    private val manifest =
        LocalStackManifest(
            toolVersion = "7",
            mode = LocalStackSpec.Mode.Live.MANIFEST_NAME,
            homeTenant = "default",
            tenants = listOf("default"),
            grafanaPort = 3300,
            documentsPort = 3380,
            files = emptyMap(),
        )

    /**
     * A Grafana that serves the tree on disk only from its [loadedOnReload]th reload on, as one whose
     * earlier reloads walked a stale, empty view of the tree.
     */
    private class LaggingGrafana(
        private val loadedOnReload: Int,
    ) : LocalGrafana {
        var reloads = 0
        val lookups = mutableListOf<String>()
        var loaded: Set<String> = emptySet()
        lateinit var onDisk: () -> Set<String>

        override fun isRunning(port: Int): Boolean = true

        override fun reloadDatasources(port: Int) = Unit

        override fun reloadDashboards(port: Int) {
            reloads++
            loaded = if (reloads >= loadedOnReload) onDisk() else emptySet()
        }

        override fun servesDashboard(
            port: Int,
            uid: String,
        ): Boolean {
            lookups += uid
            return uid in loaded
        }
    }

    private fun source(tree: Map<String, String>) =
        object : DashboardTreeSource {
            override val origin: String = "test"

            override fun render(context: DashboardInstallContext): Map<String, String> = tree
        }

    private val tree = mapOf("core/a.json" to """{"uid":"a"}""", "core/b.json" to """{"uid":"b","title":"B"}""")

    private fun dashboards(grafana: LaggingGrafana): LocalStackDashboards {
        grafana.onDisk = { setOf("a", "b") }
        return LocalStackDashboards(grafana, source(tree), reloadAttempts = 4, reloadInterval = Duration.ofMillis(1))
    }

    @Test
    fun `a reload that loaded nothing is followed by another until Grafana serves the tree`() {
        val grafana = LaggingGrafana(loadedOnReload = 3)

        val installed = dashboards(grafana).install(stack, manifest, source = null, reload = true)

        assertThat(grafana.reloads).isEqualTo(3)
        assertThat(installed.reloaded).isTrue()
    }

    @Test
    fun `a Grafana that serves the tree after the first reload is reloaded once`() {
        val grafana = LaggingGrafana(loadedOnReload = 1)

        dashboards(grafana).install(stack, manifest, source = null, reload = true)

        assertThat(grafana.reloads).isEqualTo(1)
        assertThat(grafana.lookups).containsExactlyInAnyOrder("a", "b")
    }

    @Test
    fun `a tree Grafana never serves fails after the last reload, naming the missing dashboards`() {
        val grafana = LaggingGrafana(loadedOnReload = Int.MAX_VALUE)

        assertThatThrownBy { dashboards(grafana).install(stack, manifest, source = null, reload = true) }
            .isInstanceOfSatisfying(GrafanaDashboardsNotServed::class.java) {
                assertThat(it.missing).containsExactly("a", "b")
                assertThat(it.reloads).isEqualTo(4)
            }
        assertThat(grafana.reloads).isEqualTo(4)
    }

    @Test
    fun `a dashboard with no uid is not looked up, since Grafana gives it a new one`() {
        val grafana = LaggingGrafana(loadedOnReload = 1).apply { onDisk = { emptySet() } }
        val dashboards =
            LocalStackDashboards(
                grafana,
                source(mapOf("mine/loose.json" to """{"title":"no uid"}""")),
                reloadAttempts = 4,
                reloadInterval = Duration.ofMillis(1),
            )

        dashboards.install(stack, manifest, source = null, reload = true)

        assertThat(grafana.reloads).isEqualTo(1)
        assertThat(grafana.lookups).isEmpty()
    }

    @Test
    fun `an install with no reload asks Grafana nothing`() {
        val grafana = LaggingGrafana(loadedOnReload = 1)

        dashboards(grafana).install(stack, manifest, source = null, reload = false)

        assertThat(grafana.reloads).isZero()
        assertThat(grafana.lookups).isEmpty()
    }
}
