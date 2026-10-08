package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.GrafanaAnnotationRequest
import com.rustyrazorblade.easydblab.services.GrafanaAnnotationResponse
import com.rustyrazorblade.easydblab.services.GrafanaClient
import com.rustyrazorblade.easydblab.services.KitMetrics
import com.rustyrazorblade.easydblab.services.MetricsRegistryService
import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.io.File

/**
 * A kit `start` whose steps all succeed still fails when its metrics registration or any of its
 * dashboards does not reach the cluster (kit-metrics-declaration, typed-install-steps). Both
 * collaborators return a Kotlin `Result`, which Mockito cannot stub as a failure, so this test
 * uses fakes that return real failures.
 */
class KitRunnerCommandStartFailureTest : KitRunnerCommandTestBase() {
    private val metrics = FakeMetricsRegistry()
    private val grafana = FakeGrafanaClient()

    override fun additionalTestModules(): List<Module> =
        super.additionalTestModules() +
            module {
                single<MetricsRegistryService> { metrics }
                single<GrafanaClient> { grafana }
            }

    private fun writeKit(
        metricsYaml: String = "",
        dashboardsYaml: String = "",
    ) {
        writeKitYaml(
            "mydb",
            "name: mydb\n$metricsYaml$dashboardsYaml" +
                """
                start:
                  - type: shell
                    script: echo hello
                """.trimIndent(),
        )
    }

    private fun writeDashboard(name: String) {
        File(File(workingDir, "mydb").also { it.mkdirs() }, name).writeText("{}")
    }

    private val scrape = "metrics:\n  - type: scrape\n    port: 9100\n"

    @Test
    fun `start succeeds when metrics register and every dashboard installs`() {
        writeDashboard("overview.json")
        writeKit(scrape, "dashboards:\n  - path: overview.json\n")

        assertThat(command("mydb", "start").call()).isEqualTo(0)
        assertThat(grafana.installed).isEqualTo(1)
    }

    @Test
    fun `a failed metrics registration emits an event naming the kit and fails start`() {
        metrics.registerResult = Result.failure(IllegalStateException("configmap write refused"))
        writeKit(scrape)

        var exit = 0
        val events = captureEvents { exit = command("mydb", "start").call() }

        val failed = events.filterIsInstance<Event.Kit.MetricsRegistrationFailed>().single()
        assertThat(failed.kit).isEqualTo("mydb")
        assertThat(failed.reason).contains("configmap write refused")
        assertThat(failed.isError()).isTrue()
        assertThat(exit).isEqualTo(Constants.ExitCodes.ERROR)
    }

    @Test
    fun `a dashboard Grafana rejects emits an event naming the kit and the dashboard and fails start`() {
        grafana.installResult = Result.failure(IllegalStateException("HTTP 400 invalid dashboard"))
        writeDashboard("overview.json")
        writeKit(dashboardsYaml = "dashboards:\n  - path: overview.json\n")

        var exit = 0
        val events = captureEvents { exit = command("mydb", "start").call() }

        val failed = events.filterIsInstance<Event.Grafana.KitDashboardInstallFailed>().single()
        assertThat(failed.kit).isEqualTo("mydb")
        assertThat(failed.dashboard).isEqualTo("overview.json")
        assertThat(failed.reason).contains("HTTP 400 invalid dashboard")
        assertThat(failed.isError()).isTrue()
        assertThat(exit).isEqualTo(Constants.ExitCodes.ERROR)
    }

    @Test
    fun `a declared dashboard file that is missing emits an event naming it and fails start`() {
        writeDashboard("overview.json")
        writeKit(dashboardsYaml = "dashboards:\n  - path: overview.json\n  - path: missing.json\n")

        var exit = 0
        val events = captureEvents { exit = command("mydb", "start").call() }

        val failed = events.filterIsInstance<Event.Grafana.KitDashboardInstallFailed>().single()
        assertThat(failed.dashboard).isEqualTo("missing.json")
        assertThat(exit).isEqualTo(Constants.ExitCodes.ERROR)
    }

    @Test
    fun `dashboards that cannot be read fail start`() {
        File(File(workingDir, "mydb").also { it.mkdirs() }, "overview.json").writeText("not json")
        writeKit(dashboardsYaml = "dashboards:\n  - path: overview.json\n")

        var exit = 0
        val events = captureEvents { exit = command("mydb", "start").call() }

        assertThat(events.filterIsInstance<Event.Grafana.KitDashboardsSkipped>().single().dashboards)
            .containsExactly("overview.json")
        assertThat(exit).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(grafana.installed).isZero()
    }

    @Test
    fun `a tenant listing that cannot be read fails start`() {
        whenever(mockObjectStore.listFiles(any(), any(), any())).thenThrow(IllegalStateException("S3 Access Denied"))
        writeDashboard("overview.json")
        writeKit(dashboardsYaml = "dashboards:\n  - path: overview.json\n")

        assertThat(command("mydb", "start").call()).isEqualTo(Constants.ExitCodes.ERROR)
    }

    /** Returns [registerResult] from every registration. */
    private class FakeMetricsRegistry : MetricsRegistryService {
        var registerResult: Result<Unit> = Result.success(Unit)

        override fun register(
            controlHost: ClusterHost,
            kitName: String,
            targets: List<KitMetrics.Scrape>,
        ): Result<Unit> = registerResult

        override fun deregister(
            controlHost: ClusterHost,
            kitName: String,
        ): Result<Unit> = Result.success(Unit)
    }

    /** Returns [installResult] from every dashboard install and counts the successful ones. */
    private class FakeGrafanaClient : GrafanaClient {
        var installResult: Result<Unit> = Result.success(Unit)
        var installed = 0

        override fun installDashboard(
            dashboard: JsonObject,
            controlHost: ClusterHost,
            folderName: String,
        ): Result<Unit> = installResult.onSuccess { installed++ }

        override fun createAnnotation(
            controlHost: ClusterHost,
            annotation: GrafanaAnnotationRequest,
        ): GrafanaAnnotationResponse = error("not used by kit start")

        override fun fetchAnnotations(controlHost: ClusterHost): String = error("not used by kit start")
    }
}
