package com.rustyrazorblade.easydblab.configuration.tenancy

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The install scope is re-read from `state.json` on every call, so an `init --namespace` inside the
 * REPL or the server applies to the next command that applies an object.
 */
class StateInstallScopeSourceTest {
    @Test
    fun `a namespace recorded after the source was built is used on the next call`(
        @TempDir dir: File,
    ) {
        val manager = ClusterStateManager(File(dir, "state.json"))
        val source = StateInstallScopeSource(manager)
        manager.replace(ClusterState(name = "c", clusterId = "id-1", initConfig = InitConfig()))
        assertThat(source.current()).isEqualTo(InstallScope(K8sNamespace("default"), "id-1"))

        manager.replace(ClusterState(name = "c", clusterId = "id-1", initConfig = InitConfig(namespace = "lab-a")))

        assertThat(source.current()).isEqualTo(InstallScope(K8sNamespace("lab-a"), "id-1"))
    }
}
