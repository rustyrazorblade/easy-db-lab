package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import io.github.resilience4j.retry.Retry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Duration

/**
 * Has a running Grafana reload a dashboard tree just renamed into place, and returns only once it
 * serves the tree.
 *
 * Grafana's answer to the reload says nothing about what it read. Right after the rename, Docker
 * Desktop's file sharing can still show the container the old, removed `current`, empty, for a few
 * hundred milliseconds; Grafana 13.2.2's walk of it finds no dashboard, deletes every one it had
 * provisioned, and answers 200. Nothing walks the tree again: with an update interval of 10s or less
 * the provider watches for file events instead of polling, and none come. So after each reload every
 * dashboard of the tree that has a uid is looked up, and the reload is asked again until all are
 * served. A dashboard with no uid is not looked up: Grafana gives it a new uid on every load.
 *
 * @property grafana the stack's Grafana.
 * @property attempts how many reloads to ask before giving up.
 * @property interval the wait between reloads.
 */
class DashboardReload(
    private val grafana: LocalGrafana,
    private val attempts: Int,
    private val interval: Duration,
) {
    /**
     * Reloads the Grafana on [port] until it serves every dashboard of [tree] (JSON by path) that has
     * a uid.
     *
     * @throws GrafanaReloadRefused when Grafana answers a reload with an error.
     * @throws GrafanaDashboardsNotServed when Grafana still does not serve them after the last reload.
     */
    fun reload(
        port: Int,
        tree: Map<String, String>,
    ) {
        val uids = tree.values.mapNotNull(::uidOf)
        var missing = uids
        val served =
            Retry
                .of("dashboard-reload", RetryUtil.createBooleanPollRetryConfig(attempts, interval))
                .executeSupplier {
                    grafana.reloadDashboards(port)
                    missing = uids.filterNot { grafana.servesDashboard(port, it) }
                    missing.isEmpty()
                }
        if (!served) throw GrafanaDashboardsNotServed(missing, attempts)
    }

    private fun uidOf(json: String): String? =
        (Json.parseToJsonElement(json).jsonObject["uid"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() }
}
