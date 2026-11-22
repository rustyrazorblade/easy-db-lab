package com.rustyrazorblade.easydblab.commands.kit

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.commands.install.KitRunnerCommandTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** `kit uninstall` runs the kit's uninstall phase and exits with that phase's result. */
class UninstallTest : KitRunnerCommandTestBase() {
    private val kitYaml =
        """
        name: mydb
        uninstall:
          - type: shell
            script: echo uninstall
        """.trimIndent()

    private fun uninstall(name: String = "mydb"): Int = Uninstall().apply { kit = name }.call()

    @Test
    fun `a failed uninstall phase makes kit uninstall exit non-zero`() {
        writeKitYaml("mydb", kitYaml)
        whenever(mockWorkloadStepExecutor.execute(any(), any(), any())).thenReturn(Result.failure(IllegalStateException("boom")))

        assertThat(uninstall()).isEqualTo(Constants.ExitCodes.ERROR)
    }

    @Test
    fun `a successful uninstall exits zero`() {
        writeKitYaml("mydb", kitYaml)

        assertThat(uninstall()).isEqualTo(0)
    }

    @ParameterizedTest
    @ValueSource(strings = [".", "..", "a/../..", "../sibling", "a/b", "my kit", "-x", "a/"])
    fun `a kit name that is not a directory name is refused and deletes nothing`(name: String) {
        File(workingDir, "a/b").mkdirs()
        File(workingDir, "a").mkdirs()
        File(workingDir.parentFile, "sibling").mkdirs()
        val insideWorkspace = File(workingDir, "keep-me").apply { writeText("workspace") }
        val aboveWorkspace = File(workingDir.parentFile, "keep-me-too").apply { writeText("parent") }

        val outcome = runCatching { uninstall(name) }

        assertThat(insideWorkspace).hasContent("workspace")
        assertThat(aboveWorkspace).hasContent("parent")
        assertThat(File(workingDir.parentFile, "sibling")).isDirectory()
        assertThat(File(workingDir, "a/b")).isDirectory()
        assertThat(outcome.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid kit name '$name'")
    }

    @Test
    fun `a kit name that differs from the installed kit only by case is refused and deletes nothing`() {
        writeKitYaml("mydb", kitYaml)

        val outcome = runCatching { uninstall("MYDB") }

        assertThat(File(workingDir, "mydb/${Constants.Kit.CONFIG_FILE}")).hasContent(kitYaml)
        assertThat(outcome.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Kit 'MYDB' is not installed")
    }

    /**
     * A workspace directory that holds no `kit.yaml` is the owner's data (fetched profiles, logs,
     * the tool wrappers, provisioning files), never a kit, so it is refused and kept. A Cassandra
     * conf directory such as `5.0` never gets this far: it is not a kit name.
     */
    @ParameterizedTest
    @ValueSource(strings = ["profiles", "logs", "bin", "provisioning", "k8s"])
    fun `a workspace directory that is not an installed kit is refused and deletes nothing`(name: String) {
        val dir = File(workingDir, name).apply { mkdirs() }
        val owned = File(dir, "flamegraph.html").apply { writeText("owner") }

        val outcome = runCatching { uninstall(name) }

        assertThat(outcome.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Kit '$name' is not installed")
        assertThat(owned).hasContent("owner")
    }

    @Test
    fun `a kit directory that cannot be deleted fails kit uninstall and names the directory`() {
        writeKitYaml("mydb", kitYaml)
        val locked = File(workingDir, "mydb/locked").apply { mkdirs() }
        File(locked, "f").writeText("x")
        assertThat(locked.setWritable(false)).isTrue()
        try {
            val outcome = runCatching { uninstall() }

            assertThat(outcome.exceptionOrNull())
                .isInstanceOf(IOException::class.java)
                .hasMessageContaining("Could not delete ${File(workingDir, "mydb").absolutePath}")
            assertThat(File(locked, "f")).exists()
        } finally {
            locked.setWritable(true)
        }
    }

    @Test
    fun `uninstalling a kit directory that is a link removes the link and keeps its target`() {
        val outside = File(workingDir.parentFile, "kit-checkout").apply { mkdirs() }
        File(outside, Constants.Kit.CONFIG_FILE).writeText(kitYaml)
        val precious = File(outside, "precious.txt").apply { writeText("source") }
        val link = File(workingDir, "mydb")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertThat(uninstall()).isEqualTo(0)

        assertThat(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS)).isFalse()
        assertThat(precious).hasContent("source")
    }

    @Test
    fun `uninstalling a kit with no uninstall phase removes a link to the kit and keeps its target`() {
        val outside = File(workingDir.parentFile, "kit-checkout").apply { mkdirs() }
        File(outside, Constants.Kit.CONFIG_FILE).writeText("name: mydb\n")
        val precious = File(outside, "precious.txt").apply { writeText("source") }
        val link = File(workingDir, "mydb")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertThat(uninstall()).isEqualTo(0)

        assertThat(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS)).isFalse()
        assertThat(precious).hasContent("source")
    }

    @Test
    fun `uninstalling a kit removes a link inside it and keeps the link's target`() {
        writeKitYaml("mydb", kitYaml)
        val outside = File(workingDir.parentFile, "shared-data").apply { mkdirs() }
        val precious = File(outside, "precious.txt").apply { writeText("shared") }
        Files.createSymbolicLink(File(workingDir, "mydb/data").toPath(), outside.toPath())

        assertThat(uninstall()).isEqualTo(0)

        assertThat(File(workingDir, "mydb")).doesNotExist()
        assertThat(precious).hasContent("shared")
    }
}
