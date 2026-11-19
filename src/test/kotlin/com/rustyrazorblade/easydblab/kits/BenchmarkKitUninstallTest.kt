package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * The benchmark kits create pods with `kubectl run` that outlive their run: a failed `prepare`
 * leaves a pod in Error, a finished `start` leaves one in Succeeded. `kit uninstall` must remove
 * all of them, by the `easydblab/kit` label every one of those pods carries; without an uninstall
 * phase they stayed on the cluster after the kit was gone.
 */
class BenchmarkKitUninstallTest : BaseKoinTest() {
    @ParameterizedTest
    @ValueSource(strings = ["sysbench", "clickhouse-benchmark"])
    fun `uninstall deletes the kit's pods by the label every one of them carries`(kit: String) {
        val config =
            BuiltinKitFixture(kit, TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get())).config

        val deletes = config.uninstall.filterIsInstance<InstallStep.Delete>()

        assertThat(deletes).anySatisfy {
            assertThat(it.selector).isEqualTo("easydblab/kit=\${KIT_NAME}")
            assertThat(it.kinds).contains("pod")
        }
    }
}
