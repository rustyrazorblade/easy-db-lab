package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * Putting a dashboard tree where a local stack's Grafana reads it: staged beside the provisioned
 * tree, inside the directory Grafana mounts, then renamed into place, so Grafana never reads a
 * half-written tree.
 */
class DashboardTreeInstallerTest {
    @TempDir
    lateinit var stack: File

    private val dashboards get() = File(stack, Constants.LocalStack.DASHBOARDS_DIR)
    private val current get() = File(dashboards, Constants.LocalStack.DASHBOARDS_TREE)

    private fun filesUnder(dir: File): List<String> =
        dir
            .walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .sorted()
            .toList()

    @Test
    fun `a first install writes the tree as the provisioned tree`() {
        DashboardTreeInstaller.install(stack, mapOf("cassandra/a.json" to "{}", "home/h.json" to "{\"h\":1}"))

        assertThat(filesUnder(current)).containsExactly("cassandra/a.json", "home/h.json")
        assertThat(File(current, "home/h.json").readText()).isEqualTo("{\"h\":1}")
    }

    @Test
    fun `a new tree replaces the old one whole, and nothing is left beside it`() {
        DashboardTreeInstaller.install(stack, mapOf("old/gone.json" to "{}", "kept/k.json" to "{\"v\":1}"))

        DashboardTreeInstaller.install(stack, mapOf("kept/k.json" to "{\"v\":2}"))

        assertThat(filesUnder(current)).containsExactly("kept/k.json")
        assertThat(File(current, "kept/k.json").readText()).isEqualTo("{\"v\":2}")
        assertThat(dashboards.list()).containsExactly(Constants.LocalStack.DASHBOARDS_TREE)
    }

    @Test
    fun `when the new tree cannot be renamed into place, the old tree is put back`() {
        DashboardTreeInstaller.install(stack, mapOf("kept/k.json" to "{\"v\":1}"))

        assertThatThrownBy {
            DashboardTreeInstaller.install(stack, mapOf("kept/k.json" to "{\"v\":2}"), moveIntoPlace = {
                _,
                _,
                ->
                throw IOException("disk said no")
            })
        }.isInstanceOf(IOException::class.java)

        assertThat(filesUnder(current)).containsExactly("kept/k.json")
        assertThat(File(current, "kept/k.json").readText()).isEqualTo("{\"v\":1}")
        assertThat(dashboards.list()).containsExactly(Constants.LocalStack.DASHBOARDS_TREE)
    }

    @Test
    fun `an old tree left aside by an install that was stopped is removed by the next install`() {
        File(dashboards, ".previous-123/old/o.json").apply { parentFile.mkdirs() }.writeText("{}")

        DashboardTreeInstaller.install(stack, mapOf("kept/k.json" to "{}"))

        assertThat(dashboards.list()).containsExactly(Constants.LocalStack.DASHBOARDS_TREE)
    }

    @Test
    fun `a tree with a path outside one folder is refused before the provisioned tree changes`() {
        DashboardTreeInstaller.install(stack, mapOf("kept/k.json" to "{}"))

        assertThatThrownBy { DashboardTreeInstaller.install(stack, mapOf("ok/a.json" to "{}", "../escape.json" to "{}")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("../escape.json")
        assertThat(filesUnder(current)).containsExactly("kept/k.json")
        assertThat(dashboards.list()).containsExactly(Constants.LocalStack.DASHBOARDS_TREE)
    }
}
