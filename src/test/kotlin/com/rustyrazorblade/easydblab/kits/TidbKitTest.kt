package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The TiDB kit's cleanup. TiDB Operator creates a `local-path` PVC for PD and for every TiKV and
 * TiFlash store, and keeps them when the TidbCluster is deleted, so an uninstall that only removed
 * the operator left seven PVCs (and their volumes) behind.
 */
class TidbKitTest : BaseKoinTest() {
    private val kit by lazy {
        BuiltinKitFixture("tidb", TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))
    }

    @Test
    fun `uninstall deletes the PVCs the operator labels with the cluster's instance`() {
        val uninstall = kit.config.uninstall
        val pvcDelete =
            uninstall
                .filterIsInstance<InstallStep.Delete>()
                .single { it.bySelector && "PersistentVolumeClaim" in it.kinds }

        // TiDB Operator labels every component's pods and PVCs app.kubernetes.io/instance=<cluster>,
        // the label the kit's runtime already selects its pods by.
        assertThat(parseLabelSelector(pvcDelete.selector)).isEqualTo(parseLabelSelector(requireNotNull(kit.config.runtime).selector))
        assertThat(pvcDelete.namespace ?: "default").isEqualTo(requireNotNull(kit.config.runtime).namespace)

        // Not under a running cluster: the guard that refuses while a TidbCluster exists runs first.
        val guard = uninstall.indexOfFirst { it is InstallStep.Shell && "get tidbcluster" in it.script }
        assertThat(guard).isNotNegative()
        assertThat(uninstall.indexOf(pvcDelete)).isGreaterThan(guard)
    }
}
