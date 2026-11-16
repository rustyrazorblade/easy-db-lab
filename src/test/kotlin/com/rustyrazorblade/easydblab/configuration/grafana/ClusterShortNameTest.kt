package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every place a dashboard shows a cluster shows its short name, `<name>-<first 8 of id>` (owner
 * decision, D3), while queries, links and document URLs keep the full `<name>-<uuid>`.
 *
 * - A cluster picker splits each option: the regex's `text` group is the short name and its `value`
 *   group the full id.
 * - A title, description, legend, annotation title or text panel names a picker only as
 *   `${name:text}`; `$name` and `${name}` render the full id. Queries, link URLs and a text panel's
 *   `src`/`href` attributes are not shown text and keep it.
 * - A table hides the `cluster` field and shows `cluster_name`; its links still read `cluster`.
 */
class ClusterShortNameTest {
    @Test
    fun `a shown cluster reference without the text format is reported`() {
        assertThat(shownProblems("Summary: baseline \${baseline_cluster}")).hasSize(1)
        assertThat(shownProblems("Ladder — \$cluster (\${latency_unit:text})")).hasSize(1)
        assertThat(shownProblems("candidate \${candidate_cluster:text}")).isEmpty()
        assertThat(shownProblems("tag_cluster_id=~\"\$cluster_id\"")).isEmpty()
        assertThat(shownProblems("""<iframe src="/reports/${'$'}{doc_tenant}/${'$'}{cluster}/index.html"></iframe>""")).isEmpty()
    }

    @Test
    fun `a cluster picker without the text and value split is reported`() {
        val split = """{"name": "cluster", "regex": "/^(?<value>(?<text>.+?)(?:-[0-9a-f]{4})?)${'$'}/"}"""

        assertThat(pickerProblems(parse("""{"name": "cluster", "regex": ""}"""))).hasSize(1)
        assertThat(pickerProblems(parse(split))).isEmpty()
        assertThat(pickerProblems(parse("""{"name": "hostname", "regex": ""}"""))).isEmpty()
    }

    @Test
    fun `a table that shows the cluster field is reported`() {
        val organize = """{"id": "organize", "options": {"indexByName": {"cluster": 0}}}"""
        val hidden =
            """{"matcher": {"id": "byName", "options": "cluster"}, "properties": [{"id": "custom.hidden", "value": true}]}"""

        assertThat(tableProblems(parse("""{"type": "table", "transformations": [$organize]}"""))).hasSize(1)
        assertThat(
            tableProblems(parse("""{"type": "table", "transformations": [$organize], "fieldConfig": {"overrides": [$hidden]}}""")),
        ).isEmpty()
        assertThat(
            tableProblems(
                parse("""{"type": "table", "transformations": [{"id": "organize", "options": {"excludeByName": {"cluster": true}}}]}"""),
            ),
        ).isEmpty()
        assertThat(
            tableProblems(
                parse("""{"type": "table", "transformations": [{"id": "organize", "options": {"excludeByName": {"Time": true}}}]}"""),
            ),
        ).describedAs("a table that never names the cluster field").isEmpty()
    }

    @Test
    fun `every dashboard shows clusters by their short name`() {
        val found =
            DashboardFiles.all().flatMap { file ->
                val dashboard = parse(file.readText())
                problems(dashboard).map { "${file.path} $it" }
            }

        assertThat(found).isEmpty()
    }

    private fun problems(dashboard: JsonObject): List<String> {
        val panels = panels(dashboard)
        val shown =
            panels.flatMap { panel ->
                val title = panel.text("title").orEmpty()
                val texts =
                    listOfNotNull(panel.text("title"), panel.text("description"), (panel["options"] as? JsonObject)?.text("content")) +
                        (panel["targets"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("legendFormat") }
                texts.flatMap { text -> shownProblems(text).map { "panel '$title': $it" } } +
                    tableProblems(panel).map { "panel '$title': $it" }
            }
        val annotations =
            objects((dashboard["annotations"] as? JsonObject)?.get("list")).flatMap { annotation ->
                listOfNotNull(annotation.text("titleFormat"), annotation.text("textFormat"))
                    .flatMap { text -> shownProblems(text).map { "annotation '${annotation.text("name")}': $it" } }
            }
        val pickers = objects((dashboard["templating"] as? JsonObject)?.get("list")).flatMap { pickerProblems(it) }
        return shown + annotations + pickers
    }

    /** The cluster references in [text] that render the full id; a `src` or `href` value is a URL, not shown. */
    private fun shownProblems(text: String): List<String> =
        CLUSTER_REFERENCE
            .findAll(text.replace(URL_ATTRIBUTE, ""))
            .filter { it.groupValues[2] != "text" }
            .map { "'${it.value}' shows the full cluster id; use \${${it.groupValues[1]}:text}" }
            .toList()

    private fun pickerProblems(variable: JsonObject): List<String> {
        val name = variable.text("name")
        if (name !in PICKERS) return emptyList()
        val regex = variable.text("regex").orEmpty()
        return listOfNotNull(
            "picker '$name' shows the full cluster id: its regex needs the (?<text>...) and (?<value>...) groups"
                .takeUnless { "(?<text>" in regex && "(?<value>" in regex },
        )
    }

    /**
     * A table that names the `cluster` field (in its `organize` or an override) shows that column
     * unless `organize` excludes it or an override hides it; the short name is `cluster_name`.
     */
    private fun tableProblems(panel: JsonObject): List<String> {
        if (panel.text("type") != "table") return emptyList()
        val organizeOptions =
            objects(panel["transformations"]).filter { it.text("id") == "organize" }.mapNotNull { it["options"] as? JsonObject }
        val clusterOverrides =
            objects((panel["fieldConfig"] as? JsonObject)?.get("overrides")).filter { override ->
                (override["matcher"] as? JsonObject)?.text("options") == BY_NAME_CLUSTER
            }
        val named =
            clusterOverrides.isNotEmpty() ||
                organizeOptions.any { options ->
                    ORGANIZE_KEYS.any { key ->
                        (options[key] as? JsonObject)?.containsKey(BY_NAME_CLUSTER) ==
                            true
                    }
                }
        if (!named) return emptyList()
        val excluded =
            organizeOptions.any { options ->
                ((options["excludeByName"] as? JsonObject)?.get(BY_NAME_CLUSTER) as? JsonPrimitive)?.contentOrNull == "true"
            }
        val hidden =
            clusterOverrides.any { override ->
                objects(override["properties"]).any { property ->
                    property.text("id") == "custom.hidden" && (property["value"] as? JsonPrimitive)?.contentOrNull == "true"
                }
            }
        return listOfNotNull("the cluster column shows the full id: hide it and show cluster_name".takeUnless { excluded || hidden })
    }

    private fun panels(dashboard: JsonObject): List<JsonObject> {
        fun walk(list: JsonElement?): List<JsonObject> = objects(list).flatMap { listOf(it) + walk(it["panels"]) }
        return walk(dashboard["panels"])
    }

    private fun objects(element: JsonElement?): List<JsonObject> = (element as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private companion object {
        val PICKERS = setOf("cluster", "baseline_cluster", "candidate_cluster")
        const val BY_NAME_CLUSTER = "cluster"
        val ORGANIZE_KEYS = listOf("excludeByName", "indexByName", "renameByName", "includeByName")

        /** `$name`, `${name}` or `${name:format}` for a cluster picker; `$cluster_id` is another variable. */
        val CLUSTER_REFERENCE = Regex("""\$\{?(cluster|baseline_cluster|candidate_cluster)\b(?::(\w+))?\}?""")

        /** A link or frame URL in a text panel: it carries the full id and is never shown. */
        val URL_ATTRIBUTE = Regex("""\b(?:src|href)\s*=\s*"[^"]*"""")
    }
}
