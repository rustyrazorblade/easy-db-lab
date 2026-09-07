package com.rustyrazorblade.easydblab.configuration.grafana

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the contents of the core dashboard registry.
 *
 * The registry drives `grafana update-config`. A stale entry here installs a second, diverged copy
 * of a dashboard a kit already owns, and Grafana shows both without saying which is which.
 */
class GrafanaDashboardTest {
    @Test
    fun `every registered dashboard resolves to a packaged JSON resource`() {
        // The enum is the only place a dashboard is registered, and the builder loads the file from
        // the classpath by name. An entry with no file fails Grafana at deploy time, not here.
        val missing =
            GrafanaDashboard.entries.filter { dashboard ->
                GrafanaDashboard::class.java.getResource("/${dashboard.jsonFileName}") == null
            }

        assertThat(missing).isEmpty()
    }

    @Test
    fun `the registry installs no ClickHouse dashboard`() {
        // ClickHouse's dashboards belong to its kit, which installs them itself. The top-level
        // copies had already diverged from the kit's, so keeping them meant shipping two.
        val clickhouseEntries =
            GrafanaDashboard.entries.filter { dashboard ->
                dashboard.jsonFileName.contains("clickhouse", ignoreCase = true) ||
                    dashboard.configMapName.contains("clickhouse", ignoreCase = true)
            }

        assertThat(clickhouseEntries).isEmpty()
    }

    @Test
    fun `no ClickHouse dashboard is packaged at the top level`() {
        // The top-level `dashboards/` directory is a resource root, so a leftover file there would
        // still be on the classpath even with the enum entry gone.
        assertThat(GrafanaDashboard::class.java.getResource("/clickhouse.json")).isNull()
        assertThat(GrafanaDashboard::class.java.getResource("/clickhouse-logs.json")).isNull()
    }

    @Test
    fun `the ClickHouse kit still ships both of its own dashboards`() {
        // Removing the top-level copies must leave the kit's own, which the kit runner installs.
        val kitDashboards = "/com/rustyrazorblade/easydblab/kits/clickhouse/dashboards"

        assertThat(GrafanaDashboard::class.java.getResource("$kitDashboards/clickhouse.json"))
            .isNotNull()
        assertThat(GrafanaDashboard::class.java.getResource("$kitDashboards/clickhouse-logs.json"))
            .isNotNull()
    }
}
