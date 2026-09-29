package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Grafana does not lay a dashboard out in file order. It sorts the top-level panels by `gridPos` (y,
 * then x) and gives each panel to the row above it in that order. A collapsed row keeps its own
 * panels in its `panels` array, so a top-level panel that sorts under a collapsed row is hidden in
 * it, and the expanded row it was written under renders empty. This held for `clickhouse-overview`,
 * whose collapsed rows all sat at y 0 and took every panel of "ClickHouse Service Overview".
 */
class DashboardRowOrderTest {
    private data class Placed(
        val title: String,
        val type: String,
        val collapsed: Boolean,
        val y: Int,
        val x: Int,
    )

    private fun placed(panel: JsonObject): Placed {
        val gridPos = panel.getValue("gridPos").jsonObject
        return Placed(
            title = panel["title"]?.jsonPrimitive?.content.orEmpty(),
            type = panel["type"]?.jsonPrimitive?.content.orEmpty(),
            collapsed = panel["collapsed"]?.jsonPrimitive?.content == "true",
            y = gridPos.getValue("y").jsonPrimitive.int,
            x = gridPos.getValue("x").jsonPrimitive.int,
        )
    }

    private fun sortedPanels(): Map<String, List<Placed>> =
        DashboardFiles.all().associate { file ->
            val panels =
                Json
                    .parseToJsonElement(file.readText())
                    .jsonObject["panels"]
                    ?.jsonArray
                    .orEmpty()
                    .map { placed(it.jsonObject) }
            file.path to panels.sortedWith(compareBy<Placed> { it.y }.thenBy { it.x })
        }

    @Test
    fun `no top-level panel sorts under a collapsed row`() {
        for ((path, panels) in sortedPanels()) {
            val hidden =
                panels
                    .runningFold(null as Placed?) { row, panel -> if (panel.type == ROW) panel else row }
                    .drop(1)
                    .zip(panels)
                    .filter { (row, panel) -> panel.type != ROW && row?.collapsed == true }
                    .map { (row, panel) -> "'${panel.title}' under collapsed '${row?.title}'" }

            assertThat(hidden).describedAs(path).isEmpty()
        }
    }

    @Test
    fun `no two rows share a position`() {
        for ((path, panels) in sortedPanels()) {
            val shared =
                panels.filter { it.type == ROW }.groupBy { it.y }.filterValues { it.size > 1 }.mapValues { (_, rows) ->
                    rows.map { it.title }
                }

            assertThat(shared).describedAs(path).isEmpty()
        }
    }

    private companion object {
        const val ROW = "row"
    }
}
