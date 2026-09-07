package com.rustyrazorblade.easydblab.configuration.victoria

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.apps.Deployment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Verifies the retention arguments VictoriaMetrics and VictoriaLogs are deployed with.
 *
 * Observability data on a cluster is never dropped on a timer: a cluster that outlives a bounded
 * window loses its oldest data before anything backs it up. Both products read a bare retention
 * number as *months*, so these assertions check the unit is written as well as the value.
 */
class VictoriaManifestBuilderTest {
    private val builder = VictoriaManifestBuilder()

    private fun argsOf(deployment: Deployment): List<String> =
        deployment.spec.template.spec.containers
            .single()
            .args

    @Test
    fun `VictoriaMetrics is deployed with the unbounded retention period`() {
        assertThat(argsOf(builder.buildMetricsDeployment()))
            .contains("-retentionPeriod=${Constants.Observability.UNBOUNDED_RETENTION_PERIOD}")
    }

    @Test
    fun `VictoriaLogs is deployed with the unbounded retention period and future retention`() {
        val args = argsOf(builder.buildLogsDeployment())

        assertThat(args)
            .contains("-retentionPeriod=${Constants.Observability.UNBOUNDED_RETENTION_PERIOD}")
            .contains("-futureRetention=${Constants.Observability.UNBOUNDED_RETENTION_PERIOD}")
    }

    @Test
    fun `the unbounded retention value carries an explicit unit`() {
        // A bare number means MONTHS to both VictoriaMetrics and VictoriaLogs, so "100" would be a
        // little over eight years rather than a century.
        assertThat(Constants.Observability.UNBOUNDED_RETENTION_PERIOD)
            .`as`("a bare retention number means months")
            .containsPattern("^\\d+[smhdwy]$")
    }

    @Test
    fun `no deployed store carries a bounded retention period`() {
        val retentionArgs =
            (argsOf(builder.buildMetricsDeployment()) + argsOf(builder.buildLogsDeployment()))
                .filter { it.startsWith("-retentionPeriod=") || it.startsWith("-futureRetention=") }

        assertThat(retentionArgs).isNotEmpty()
        assertThat(retentionArgs).allSatisfy { arg ->
            assertThat(arg).endsWith("=${Constants.Observability.UNBOUNDED_RETENTION_PERIOD}")
        }
    }
}
