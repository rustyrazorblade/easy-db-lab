package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

/**
 * A kit script that exports `KUBECONFIG` must name the workspace's kubeconfig by its absolute path,
 * so the script reaches the cluster whatever directory it runs from.
 */
class KitScriptKubeconfigTest : BaseKoinTest() {
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "presto, bin/start.sh.template",
        "presto, bin/stop.sh.template",
        "presto, bin/uninstall.sh.template",
        "presto, bin/update-catalogs.sh.template",
        "trino, bin/start.sh.template",
        "trino, bin/stop.sh.template",
        "trino, bin/uninstall.sh.template",
        "trino, bin/update-catalogs.sh.template",
        "cqlite-flight, bin/start.sh.template",
        "cqlite-flight, bin/stop.sh.template",
    )
    fun `the exported KUBECONFIG is the workspace's absolute kubeconfig`(
        kit: String,
        script: String,
    ) {
        val fixture = BuiltinKitFixture(kit, TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))

        val exports = fixture.renderText(script).lines().filter { it.startsWith("export KUBECONFIG=") }

        val expected = File(fixture.workspace, Constants.K3s.LOCAL_KUBECONFIG).absolutePath
        assertThat(exports).containsExactly("export KUBECONFIG=\"$expected\"")
    }
}
