package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The K3s Control Plane dashboard reads only what the `k3s-etcd` scrape produces. Its queries name
 * the scrape job by the same constant the collector job is built from, so renaming the job cannot
 * leave every panel empty, and the panels of the issue are all present.
 */
class K3sControlPlaneDashboardTest {
    private val queries = exprs(Json.parseToJsonElement(File("dashboards/infrastructure/k3s-control-plane.json").readText()))

    private fun exprs(element: JsonElement): List<String> =
        when (element) {
            is JsonObject ->
                element.entries.flatMap { (key, value) ->
                    if (key == "expr" && value is JsonPrimitive) listOfNotNull(value.contentOrNull) else exprs(value)
                }
            is JsonArray -> element.flatMap { exprs(it) }
            else -> emptyList()
        }

    @Test
    fun `every panel query selects the etcd scrape job`() {
        assertThat(queries).isNotEmpty()
        queries.forEach { query ->
            assertThat(query).contains("""job="${Constants.K3s.ETCD_JOB_NAME}"""")
        }
    }

    @Test
    fun `the dashboard queries each etcd signal of the control-plane stall checklist`() {
        val all = queries.joinToString("\n")

        assertThat(all).contains(
            "etcd_disk_backend_commit_duration_seconds_bucket",
            "etcd_disk_wal_fsync_duration_seconds_bucket",
            "etcd_mvcc_db_total_size_in_bytes",
            "etcd_server_quota_backend_bytes",
            "etcd_server_leader_changes_seen_total",
            "etcd_server_proposals_failed_total",
        )
    }
}
