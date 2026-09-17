package com.rustyrazorblade.easydblab.configuration.grafana

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for the paths [GrafanaDashboard] derives from its folder and file name.
 *
 * The relative path decides which Grafana folder the dashboard lands in once the tree is copied
 * to the control node, and the resource path is where its JSON is read from. A wrong derivation
 * files a dashboard in the wrong folder or fails to find it at all.
 */
class GrafanaDashboardTest {
    private val dashboard = GrafanaDashboard(folder = "infrastructure", jsonFileName = "system-overview.json")

    @Test
    fun `stem is the file name without its extension`() {
        assertThat(dashboard.stem).isEqualTo("system-overview")
    }

    @Test
    fun `relative path is the folder directory and file name`() {
        assertThat(dashboard.relativePath).isEqualTo("infrastructure/system-overview.json")
    }

    @Test
    fun `resource path is the folder and file under the dashboards classpath prefix`() {
        assertThat(dashboard.resourcePath).isEqualTo("/dashboards/infrastructure/system-overview.json")
    }
}
