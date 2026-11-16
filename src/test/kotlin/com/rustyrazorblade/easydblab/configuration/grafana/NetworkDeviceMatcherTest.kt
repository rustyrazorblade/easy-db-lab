package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The dashboards' network panels match the primary ENI under any name its instance family gives it.
 *
 * The primary's name comes from its PCI slot: `ens5` on c7gd, `ens52` on c8gd, `enp39s0` on i7i. A
 * device filter of `ens.*` drops the i7i primary, so its network panels show nothing. Every positive
 * device filter on a `system_network_*` selector is `en.*|eth.*`, and on System Overview every
 * network selector that has a device label carries it, so no virtual interface is counted.
 */
class NetworkDeviceMatcherTest {
    private val expectedDevices = "en.*|eth.*"

    @Test
    fun `every positive device filter on a network metric matches en and eth`() {
        val wrong =
            networkSelectors(DashboardFiles.all())
                .filter { it.deviceOperator == "=~" && it.deviceValue != expectedDevices }
                .map { "${it.where}: ${it.text}" }

        assertThat(wrong).isEmpty()
    }

    @Test
    fun `the system dashboards filter their network panels to en and eth`() {
        listOf(SYSTEM_OVERVIEW, SYSTEM_AB_COMPARISON).forEach { path ->
            val filtered = networkSelectors(listOf(File(path))).filter { it.deviceOperator == "=~" }

            assertThat(filtered).describedAs(path).isNotEmpty()
            assertThat(filtered.map { it.deviceValue }).describedAs(path).containsOnly(expectedDevices)
        }
    }

    @Test
    fun `every System Overview network selector with a device label matches en and eth`() {
        val unfiltered =
            networkSelectors(listOf(File(SYSTEM_OVERVIEW)))
                .filterNot { it.metric in METRICS_WITHOUT_DEVICE }
                .filterNot { it.deviceOperator == "=~" && it.deviceValue == expectedDevices }
                .map { "${it.where}: ${it.text}" }

        assertThat(unfiltered).isEmpty()
    }

    @Test
    fun `no dashboard query names the ens prefix as the network device filter`() {
        val stale =
            DashboardFiles.all().flatMap { file ->
                queries(file).filter { it.text.contains("ens.*") }.map { "${file.path} ${it.where}: ${it.text}" }
            }

        assertThat(stale).isEmpty()
    }

    /** One `system_network_*` vector selector: its metric, its full text, and its device matcher. */
    private data class NetworkSelector(
        val where: String,
        val metric: String,
        val text: String,
        val deviceOperator: String?,
        val deviceValue: String?,
    )

    private fun queries(file: File): List<ClusterFilterGuards.Query> =
        ClusterFilterGuards
            .queries(Json.parseToJsonElement(file.readText()).jsonObject)
            .filter { it.language == ClusterFilterGuards.Language.PROMQL }

    private fun networkSelectors(files: List<File>): List<NetworkSelector> =
        files.flatMap { file ->
            queries(file).flatMap { query ->
                NETWORK_METRIC.findAll(query.text).map { match ->
                    val body = labelSet(query.text, match.range.last)
                    val device = DEVICE_MATCHER.find(body)
                    NetworkSelector(
                        where = "${file.path} ${query.where}",
                        metric = match.groupValues[1],
                        text = "${match.groupValues[1]}{$body}",
                        deviceOperator = device?.groupValues?.get(1),
                        deviceValue = device?.groupValues?.get(2),
                    )
                }
            }
        }

    /**
     * The label set that opens at [open], without its braces. Quoted strings are skipped, so a
     * `${role:pipe}` variable inside a matcher value does not end the set.
     */
    private fun labelSet(
        text: String,
        open: Int,
    ): String {
        var i = open + 1
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null && c == '\\' -> i++
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '`') -> quote = c
                quote == null && c == '}' -> return text.substring(open + 1, i)
            }
            i++
        }
        error("unclosed label set at $open in: $text")
    }

    private companion object {
        const val SYSTEM_OVERVIEW = "dashboards/infrastructure/system-overview.json"
        const val SYSTEM_AB_COMPARISON = "dashboards/infrastructure/system-ab-comparison.json"

        // host_metrics' connection count is per protocol and state; it has no device label.
        val METRICS_WITHOUT_DEVICE = setOf("system_network_connections")

        val NETWORK_METRIC = Regex("""\b(system_network_\w+)\{""")
        val DEVICE_MATCHER = Regex("""(?:^|[,\s])device\s*(=~|!~|!=|=)\s*"([^"]*)"""")
    }
}
