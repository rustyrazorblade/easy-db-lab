package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * No dashboard saves an auto-refresh.
 *
 * A saved refresh reloads every panel on its interval, and Grafana cancels the queries still
 * running. Once a query takes longer than the interval, the load never finishes: System Overview
 * at 10s left 44 panels loading after 300s, with 1,284 of 1,432 requests cancelled. A user can
 * still turn a refresh on with the picker.
 */
class DashboardRefreshTest {
    @Test
    fun `no core or kit dashboard saves an auto-refresh`() {
        val saved =
            DashboardFiles.all().mapNotNull { file ->
                val refresh = (Json.parseToJsonElement(file.readText()).jsonObject["refresh"] as? JsonPrimitive)?.contentOrNull
                "${file.path}: refresh '$refresh'".takeUnless { refresh.isNullOrEmpty() || refresh == "false" }
            }

        assertThat(saved).isEmpty()
    }
}
