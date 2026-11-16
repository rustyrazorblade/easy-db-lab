package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog

/**
 * The shipped dashboard catalog, discovered once for every test that needs it.
 *
 * Discovery is a classpath scan; the result cannot differ between tests in one JVM, so the
 * Grafana test classes share this instance instead of each scanning again.
 */
object TestDashboardCatalog {
    val catalog: GrafanaDashboardCatalog by lazy { GrafanaDashboardCatalog.discover() }
}
