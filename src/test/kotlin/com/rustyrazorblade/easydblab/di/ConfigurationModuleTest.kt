package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.TestContextFactory
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File

/**
 * The production [configurationModule] over a [Context] whose workspace is not the process's
 * current directory. Uses the production module, not a test rebinding, so a bare relative
 * `state.json` in the binding would be caught.
 */
class ConfigurationModuleTest {
    @TempDir
    lateinit var tempDir: File

    private fun withManager(
        workspace: File,
        block: (ClusterStateManager) -> Unit,
    ) {
        val context = TestContextFactory.createTestContext(tempDir, workingDirectory = workspace)
        val app = koinApplication { modules(module { single<Context> { context } }, configurationModule) }
        try {
            block(app.koin.get())
        } finally {
            app.close()
        }
    }

    @Test
    fun `state is saved and loaded in the workspace, not the current directory`() {
        val workspace = File(tempDir, "w").also { it.mkdirs() }
        withManager(workspace) { manager ->
            manager.replace(ClusterState(name = "lab"))
            assertThat(manager.load().name).isEqualTo("lab")
        }
        assertThat(File(workspace, "state.json")).exists()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["load", "update"])
    fun `missing state names the absolute path of the workspace state file`(operation: String) {
        val workspace = File(tempDir, "w").also { it.mkdirs() }
        withManager(workspace) { manager ->
            val read: () -> Unit =
                when (operation) {
                    "load" -> {
                        { manager.load() }
                    }
                    else -> {
                        { manager.update { } }
                    }
                }
            assertThatThrownBy { read() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining(File(workspace, "state.json").absolutePath)
                .hasMessageContaining("Run the command from the cluster's workspace")
                .hasMessageContaining("init")
        }
    }
}
