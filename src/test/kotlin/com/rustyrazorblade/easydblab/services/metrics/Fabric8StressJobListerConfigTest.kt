package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.WorkspaceKubeApiLocator
import io.fabric8.kubernetes.client.Config
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import java.io.File

/**
 * `metrics stream` lists stress jobs on the cluster the rest of the workspace targets: the context
 * pinned in cluster state, read from the workspace kubeconfig by path.
 */
@ResourceLock(Config.KUBERNETES_KUBECONFIG_FILE)
class Fabric8StressJobListerConfigTest {
    @TempDir
    lateinit var workspace: File

    @Test
    fun `the lister targets the recorded context, not the kubeconfig's current-context`() {
        File(workspace, Constants.K3s.LOCAL_KUBECONFIG).writeText(TWO_CONTEXT_KUBECONFIG)
        val manager = ClusterStateManager(File(workspace, "state.json"))
        manager.save(ClusterState(name = "test", versions = mutableMapOf()).apply { kubeContext = "pinned-context" })

        val config = Fabric8StressJobLister(WorkspaceKubeApiLocator(manager, workspace)).baseConfig()

        assertThat(config.masterUrl).startsWith("https://pinned.example:6443")
    }

    private companion object {
        val TWO_CONTEXT_KUBECONFIG =
            """
            apiVersion: v1
            clusters:
            - cluster:
                server: https://current.example:6443
                insecure-skip-tls-verify: true
              name: current
            - cluster:
                server: https://pinned.example:6443
                insecure-skip-tls-verify: true
              name: pinned
            contexts:
            - context:
                cluster: current
                user: admin
              name: current-context
            - context:
                cluster: pinned
                user: admin
              name: pinned-context
            current-context: current-context
            kind: Config
            users:
            - name: admin
              user:
                token: t
            """.trimIndent()
    }
}
