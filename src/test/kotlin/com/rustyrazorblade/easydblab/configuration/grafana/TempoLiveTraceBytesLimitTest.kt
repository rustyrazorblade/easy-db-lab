package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Keeps the Tempo dashboard's back pressure panels tied to what they describe.
 *
 * The red line on "Live Trace Bytes" is Tempo's own `live_store.max_live_traces_bytes`, and the
 * collector span-loss query is written once on the Tempo dashboard and once on Trace RED. Each pair
 * lives in different files, so these tests stop the dashboard from showing a limit Tempo no longer
 * uses, and the two copies of the query from drifting apart.
 */
class TempoLiveTraceBytesLimitTest {
    private fun panels(path: String): List<JsonObject> =
        Json
            .parseToJsonElement(File(path).readText())
            .jsonObject
            .getValue("panels")
            .jsonArray
            .map { it.jsonObject }

    @Test
    fun `the live trace bytes threshold equals max_live_traces_bytes in the live_store block of the Tempo config`() {
        val config = File("src/main/resources/com/rustyrazorblade/easydblab/configuration/tempo/tempo.yaml").readText()
        val configured =
            Regex("""(?m)^[ \t]+max_live_traces_bytes:\s*(\d+)\s*$""")
                .find(config.substringAfter("\nlive_store:").substringBefore("\n\n#"))
                ?.groupValues
                ?.get(1)
        assertThat(configured)
            .describedAs("tempo.yaml live_store.max_live_traces_bytes as a plain byte count")
            .isNotNull()

        val steps =
            panels("dashboards/observability/tempo.json")
                .single { it.getValue("title").jsonPrimitive.content == "Live Trace Bytes" }
                .getValue("fieldConfig")
                .jsonObject
                .getValue("defaults")
                .jsonObject
                .getValue("thresholds")
                .jsonObject
                .getValue("steps")
                .jsonArray
                .map { it.jsonObject.getValue("value") }
                .filter { it.toString() != "null" }
                .map { it.jsonPrimitive.content }

        assertThat(steps).containsExactly(configured)
    }

    @Test
    fun `the collector span loss query is the same on the Tempo and Trace RED dashboards`() {
        fun lossExpr(
            path: String,
            title: String,
            refId: String,
        ): String =
            panels(path)
                .single { it.getValue("title").jsonPrimitive.content == title }
                .getValue("targets")
                .jsonArray
                .map { it.jsonObject }
                .single { it.getValue("refId").jsonPrimitive.content == refId }
                .getValue("expr")
                .jsonPrimitive
                .content

        val tempo = lossExpr("dashboards/observability/tempo.json", "Spans Lost Sending to Tempo (collector)", "A")
        val traceRed = lossExpr("dashboards/cassandra/trace-red.json", "Spans Discarded, Refused and Truncated (per second)", "E")

        assertThat(traceRed).isEqualTo(tempo)
    }
}
