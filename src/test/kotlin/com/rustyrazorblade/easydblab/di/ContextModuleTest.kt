package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.CLUSTER_STATE
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.dsl.koinApplication
import java.io.File

/**
 * The production context wiring takes the start directory as a parameter: whatever directory it is
 * given is the workspace, and the production state binding writes `state.json` there, not in the
 * test JVM's current directory or the user directory.
 */
class ContextModuleTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a context built from a start directory resolves workspace files under it`() {
        val start = File(tempDir, "start").also { it.mkdirs() }
        val user = File(tempDir, "user")
        val app = koinApplication { modules(contextModule(start, user), configurationModule) }
        try {
            val context = app.koin.get<Context>()
            app.koin.get<ClusterStateManager>().replace(ClusterState(name = "lab"))

            assertThat(context.workingDirectory).isEqualTo(start)
            assertThat(File(start, CLUSTER_STATE)).isFile()
            assertThat(File(user, CLUSTER_STATE)).doesNotExist()
        } finally {
            app.close()
        }
    }
}
