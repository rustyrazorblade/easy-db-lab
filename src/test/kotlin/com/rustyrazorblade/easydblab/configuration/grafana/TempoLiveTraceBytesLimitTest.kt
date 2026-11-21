package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The red line on the Tempo dashboard's "Live Trace Bytes" panel is Tempo's own
 * `live_store.max_live_traces_bytes`. The two live in different files, so this test keeps the
 * dashboard from showing a limit Tempo no longer uses.
 */
class TempoLiveTraceBytesLimitTest {
    @Test
    fun `the live trace bytes threshold equals max_live_traces_bytes in the Tempo config`() {
        val configured =
            Regex("""(?m)^\s*max_live_traces_bytes:\s*(\d+)""")
                .find(File("src/main/resources/com/rustyrazorblade/easydblab/configuration/tempo/tempo.yaml").readText())
                ?.groupValues
                ?.get(1)
        requireNotNull(configured) { "tempo.yaml sets no live_store.max_live_traces_bytes" }

        val panel =
            Json
                .parseToJsonElement(File("dashboards/observability/tempo.json").readText())
                .jsonObject
                .getValue("panels")
                .jsonArray
                .map { it.jsonObject }
                .single { it.getValue("title").jsonPrimitive.content == "Live Trace Bytes" }
        val steps =
            panel
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
}
