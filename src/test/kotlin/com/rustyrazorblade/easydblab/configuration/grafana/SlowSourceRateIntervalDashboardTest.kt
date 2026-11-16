package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.time.Duration

/**
 * Panels whose source reports once a minute must keep two samples inside every `rate()` window.
 *
 * Grafana sets `$__rate_interval` to `max($__interval + scrape_interval, 4 * scrape_interval)`, and
 * the metrics datasource sets no `timeInterval`, so Grafana assumes a 15s scrape. A 60s panel
 * interval gives a 75s window, which holds two 60s-cadence samples only when they fall early in
 * each step, so the panel mostly shows No data. A panel interval of at least 2m gives a window of
 * at least 135s, which always holds two.
 */
class SlowSourceRateIntervalDashboardTest {
    private fun panel(
        path: String,
        id: Int,
    ): JsonObject =
        Json
            .parseToJsonElement(File(path).readText())
            .jsonObject
            .getValue("panels")
            .jsonArray
            .map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.int == id }

    private fun parseGrafanaDuration(value: String): Duration {
        val match = Regex("""^(\d+)(ms|s|m|h|d)$""").matchEntire(value)
        requireNotNull(match) { "not a Grafana duration: $value" }
        val amount = match.groupValues[1].toLong()
        return when (match.groupValues[2]) {
            "ms" -> Duration.ofMillis(amount)
            "s" -> Duration.ofSeconds(amount)
            "m" -> Duration.ofMinutes(amount)
            "h" -> Duration.ofHours(amount)
            else -> Duration.ofDays(amount)
        }
    }

    @ParameterizedTest(name = "{0} panel {1} ({2})")
    @CsvSource(
        "dashboards/observability/profiler-health.json, 17, Attach Outcomes per Second by \$groupby",
        "dashboards/observability/profiler-health.json, 20, Ship Outcomes per Second by \$groupby",
        "dashboards/observability/profiler-health.json, 24, Prune Rate by Reason (chunks/s)",
        "dashboards/infrastructure/emr.json, 22, GC Duration Rate",
    )
    fun `a panel over a 60s source asks for at least a 2m interval`(
        path: String,
        id: Int,
        title: String,
    ) {
        val panel = panel(path, id)
        assertThat(panel.getValue("title").jsonPrimitive.content).isEqualTo(title)

        assertThat(panel).describedAs("panel '$title' sets an interval").containsKey("interval")
        val interval = panel.getValue("interval").jsonPrimitive.content
        assertThat(parseGrafanaDuration(interval))
            .describedAs("panel interval of '$title'")
            .isGreaterThanOrEqualTo(Duration.ofMinutes(2))
    }
}
