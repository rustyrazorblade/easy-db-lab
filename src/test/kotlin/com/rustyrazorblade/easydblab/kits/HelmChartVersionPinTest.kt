package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

/**
 * Checks that the trino and presto kits install their helm charts at a pinned version.
 * `KitWorkloadProbe` decides a helm-runtime kit is gone by looking for pods labelled
 * `app.kubernetes.io/instance=<release>`, which was verified only for these chart versions; an
 * unpinned install would pull whatever the chart repo publishes next and could silently drop it.
 *
 * The helm command itself lives in `edl_helm_upgrade_with_catalogs` (edl-kit-lib.sh), so this test
 * checks that each kit's update-catalogs script pins the version and passes it to that function;
 * `presto-trino-scripts.test.sh` checks that helm is then run with `--version <the pin>`.
 */
class HelmChartVersionPinTest : BaseKoinTest() {
    @ParameterizedTest(name = "{0} installs {1} at {3}")
    @CsvSource(
        "trino, trinodb/trino, TRINO_CHART_VERSION, 1.42.2",
        "presto, prestodb/presto, PRESTO_CHART_VERSION, 0.4.0",
    )
    fun `every helm install of the kit's chart pins the verified version`(
        kitName: String,
        chart: String,
        versionVariable: String,
        version: String,
    ) {
        val script =
            BuiltinKitFixture(kitName, TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))
                .resource("bin/update-catalogs.sh.template")

        assertThat(Regex("""(?m)^$versionVariable="([^"]+)"$""").find(script)?.groupValues?.get(1))
            .isEqualTo(version)
        assertThat(script).contains("$chart \"\${$versionVariable}\"")
    }
}
