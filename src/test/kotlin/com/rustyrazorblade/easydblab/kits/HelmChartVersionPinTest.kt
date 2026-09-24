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
        val installs = helmInstalls(script, chart)

        assertThat(Regex("""(?m)^$versionVariable="([^"]+)"$""").find(script)?.groupValues?.get(1))
            .isEqualTo(version)
        assertThat(installs).isNotEmpty()
        assertThat(installs).allSatisfy { assertThat(it).contains("--version \"\${$versionVariable}\"") }
    }

    /** Each `helm upgrade --install` command naming [chart], joined across its `\` continuations. */
    private fun helmInstalls(
        script: String,
        chart: String,
    ): List<String> =
        script
            .replace(Regex("""\\\n\s*"""), " ")
            .lines()
            .filter { it.contains("helm upgrade --install") && it.contains(chart) }
}
