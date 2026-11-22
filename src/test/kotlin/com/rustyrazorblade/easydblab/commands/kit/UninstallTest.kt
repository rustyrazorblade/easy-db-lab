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
    @ValueSource(strings = [".", "..", "a/../..", "../sibling"])
    fun `a kit name that is not a directory name is refused and deletes nothing`(name: String) {
        File(workingDir, "a").mkdirs()
        File(workingDir.parentFile, "sibling").mkdirs()
        val insideWorkspace = File(workingDir, "keep-me").apply { writeText("workspace") }
        val aboveWorkspace = File(workingDir.parentFile, "keep-me-too").apply { writeText("parent") }

        val outcome = runCatching { uninstall(name) }

        assertThat(insideWorkspace).hasContent("workspace")
        assertThat(aboveWorkspace).hasContent("parent")
        assertThat(File(workingDir.parentFile, "sibling")).isDirectory()
        assertThat(outcome.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(name)
    }
}
