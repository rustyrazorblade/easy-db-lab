package com.rustyrazorblade.easydblab.commands.kit

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.commands.install.KitRunnerCommandTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/** `kit uninstall` runs the kit's uninstall phase and exits with that phase's result. */
class UninstallTest : KitRunnerCommandTestBase() {
    private val kitYaml =
        """
        name: mydb
        uninstall:
          - type: shell
            script: echo uninstall
        """.trimIndent()

    private fun uninstall(): Int = Uninstall().apply { kit = "mydb" }.call()

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
}
