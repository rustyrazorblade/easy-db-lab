package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Putting a dashboard tree where a local stack's Grafana reads it: written file by file into the
 * provisioned tree itself, each file renamed over its old copy, and the files the new tree drops
 * removed. The provisioned directory is never renamed or removed: right after such a swap, Docker
 * Desktop's file sharing can show the container the old, removed directory, empty, and a Grafana
 * reload of that view deletes every dashboard.
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

    private fun identity(dir: File): Any = Files.readAttributes(dir.toPath(), BasicFileAttributes::class.java).fileKey()

    @Test
    fun `a first install writes the tree as the provisioned tree`() {
        DashboardTreeInstaller.install(stack, mapOf("cassandra/a.json" to "{}", "home/h.json" to "{\"h\":1}"))

        assertThat(filesUnder(current)).containsExactly("cassandra/a.json", "home/h.json")
        assertThat(File(current, "home/h.json").readText()).isEqualTo("{\"h\":1}")
    }

    @Test
    fun `a new tree adds its new files, replaces changed ones and removes dropped ones`() {
        DashboardTreeInstaller.install(
            stack,
            mapOf("old/gone.json" to "{}", "kept/k.json" to "{\"v\":1}", "kept/drop.json" to "{}"),
        )

        DashboardTreeInstaller.install(stack, mapOf("kept/k.json" to "{\"v\":2}", "new/n.json" to "{\"n\":1}"))

        assertThat(filesUnder(current)).containsExactly("kept/k.json", "new/n.json")
        assertThat(File(current, "kept/k.json").readText()).isEqualTo("{\"v\":2}")
        assertThat(File(current, "new/n.json").readText()).isEqualTo("{\"n\":1}")
        assertThat(current.list()).containsExactlyInAnyOrder("kept", "new")
        assertThat(dashboards.list()).containsExactly(Constants.LocalStack.DASHBOARDS_TREE)
    }

    @Test
    fun `the provisioned directory and a kept folder stay the same directories across installs`() {
        DashboardTreeInstaller.install(stack, mapOf("kept/k.json" to "{\"v\":1}"))
        val tree = identity(current)
        val folder = identity(File(current, "kept"))

        DashboardTreeInstaller.install(stack, mapOf("kept/k.json" to "{\"v\":2}", "other/o.json" to "{}"))
        DashboardTreeInstaller.install(stack, mapOf("kept/k.json" to "{\"v\":3}"))

        assertThat(identity(current)).isEqualTo(tree)
        assertThat(identity(File(current, "kept"))).isEqualTo(folder)
    }

    @Test
    fun `a temporary file an install that was stopped left in the tree is removed by the next install`() {
        DashboardTreeInstaller.install(stack, mapOf("kept/k.json" to "{}"))
        File(current, "kept/.k.json.123.tmp").writeText("{")

        DashboardTreeInstaller.install(stack, mapOf("kept/k.json" to "{}"))

        assertThat(filesUnder(current)).containsExactly("kept/k.json")
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
