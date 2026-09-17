package com.rustyrazorblade.easydblab.configuration.grafana

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for the names and paths [GrafanaDashboard] derives from its folder and file name.
 *
 * Every K8s object name and every mount path comes from these two strings, so a wrong derivation
 * puts a dashboard in the wrong provisioning folder or gives it a ConfigMap name K8s rejects.
 */
class GrafanaDashboardTest {
    private val dashboard = GrafanaDashboard(folder = "infrastructure", jsonFileName = "system-overview.json")

    @Test
    fun `k8s object names are derived from the file stem`() {
        assertThat(dashboard.stem).isEqualTo("system-overview")
        assertThat(dashboard.configMapName).isEqualTo("grafana-dashboard-system-overview")
        assertThat(dashboard.volumeName).isEqualTo("dashboard-system-overview")
    }

    @Test
    fun `mount path sits under the folder's provider path`() {
        assertThat(dashboard.folderPath).isEqualTo("/var/lib/grafana/dashboards-infrastructure")
        assertThat(dashboard.mountPath).isEqualTo("/var/lib/grafana/dashboards-infrastructure/system-overview")
    }

    @Test
    fun `resource path is the folder and file under the dashboards classpath prefix`() {
        assertThat(dashboard.resourcePath).isEqualTo("/dashboards/infrastructure/system-overview.json")
    }
}
