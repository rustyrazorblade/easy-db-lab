package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A documents iframe fills its Text panel and shows a report pointer. Grafana's Text panel
 * sanitizer keeps only `src`, `width` and `height` on an iframe and strips `style`, so the size
 * must be in those attributes: a styled iframe falls back to the browser default of 300x150.
 *
 * Every iframe opens an experiment's report pointer, `reports/<tenant>/<experiment>/index.html`,
 * through the documents web server: the selected experiment's (`${'$'}{experiment}`, whose value is
 * `<tenant>/<name>`), or on a pair dashboard the experiment of side A or B (`${'$'}{a_report}`,
 * `${'$'}{b_report}`).
 */
class DocumentsIframeTest {
    private val iframe = Regex("""<iframe\b[^>]*>""")
    private val height = Regex("""\bheight="(\d+)"""")
    private val src = Regex("""\bsrc="([^"]*)"""")
    private val reportPointer = Regex("""^__DOCUMENTS_URL__/reports/\$\{(experiment|a_report|b_report)}/index\.html$""")

    private data class IframePanel(
        val file: String,
        val location: String,
        val gridHeight: Int,
        val tag: String,
    )

    private fun textPanels(element: JsonElement): List<JsonObject> =
        when (element) {
            is JsonObject -> {
                val self = if (element["type"]?.jsonPrimitive?.content == "text") listOf(element) else emptyList()
                self + element.values.flatMap { textPanels(it) }
            }
            is JsonArray -> element.flatMap { textPanels(it) }
            else -> emptyList()
        }

    private fun iframePanels(): List<IframePanel> =
        DashboardFiles.all().flatMap { file ->
            textPanels(Json.parseToJsonElement(file.readText())).flatMap { panel ->
                val content =
                    panel["options"]
                        ?.jsonObject
                        ?.get("content")
                        ?.jsonPrimitive
                        ?.content
                        .orEmpty()
                val gridHeight =
                    panel
                        .getValue("gridPos")
                        .jsonObject
                        .getValue("h")
                        .jsonPrimitive.int
                iframe.findAll(content).map { IframePanel(file.path, "${file.path} panel ${panel["id"]}", gridHeight, it.value) }.toList()
            }
        }

    @Test
    fun `the documents panels hold iframes`() {
        val counts = iframePanels().groupingBy { it.file }.eachCount()

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(DOCUMENTS_IFRAMES)
        assertThat(DOCUMENTS_IFRAMES.values.sum()).isEqualTo(TOTAL_IFRAMES)
    }

    @Test
    fun `every iframe opens an experiment's report pointer`() {
        val wrong =
            iframePanels().mapNotNull { panel ->
                val path = src.find(panel.tag)?.groupValues?.get(1)
                "${panel.location}: $path".takeUnless { path != null && reportPointer.matches(path) }
            }

        assertThat(wrong).isEmpty()
    }

    @Test
    fun `every iframe is sized by attributes and carries no style`() {
        for (panel in iframePanels()) {
            assertThat(
                panel.tag,
            ).describedAs(panel.location).contains("""width="100%"""").containsPattern(height.pattern).doesNotContain("style=")
        }
    }

    @Test
    fun `every iframe is as tall as its panel's content area`() {
        for (panel in iframePanels()) {
            val pixels =
                height
                    .find(panel.tag)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
            val panelPixels = panel.gridHeight * (GRID_CELL_HEIGHT + GRID_CELL_MARGIN) - GRID_CELL_MARGIN

            assertThat(pixels).describedAs(panel.location).isEqualTo(panelPixels - PANEL_CHROME)
        }
    }

    private companion object {
        /**
         * Tests and the two Experiment dashboards show one report; each of the three pair dashboards
         * shows the reports of A and B.
         */
        val DOCUMENTS_IFRAMES =
            mapOf(
                "dashboards/infrastructure/tests.json" to 1,
                "dashboards/cassandra/experiment-cassandra.json" to 1,
                "dashboards/infrastructure/experiment-system.json" to 1,
                "dashboards/cassandra/cluster-pair.json" to 2,
                "dashboards/cassandra/ab-comparison.json" to 2,
                "dashboards/infrastructure/system-ab-comparison.json" to 2,
            )
        const val TOTAL_IFRAMES = 9

        /** Grafana's grid: a row unit is 30 px tall with an 8 px gap between units. */
        const val GRID_CELL_HEIGHT = 30
        const val GRID_CELL_MARGIN = 8

        /** The panel header (32 px), the content padding (8 px each side) and the inline iframe's baseline gap (8 px). */
        const val PANEL_CHROME = 56
    }
}
