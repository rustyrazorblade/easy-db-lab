package com.rustyrazorblade.easydblab.configuration.otel

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The shared scrape-job renderer must drop its YAML into a collector template at the exact
 * `scrape_configs` depth; a wrong indent silently produces a config the collector rejects.
 */
class PrometheusScrapeJobTest {
    private fun job(name: String) =
        PrometheusScrapeJob(
            jobName = name,
            scrapeInterval = "15s",
            metricsPath = "/metrics",
            relabelConfigs = listOf(PrometheusRelabelConfig(targetLabel = "cluster", replacement = "c")),
            staticConfigs = listOf(PrometheusStaticConfig(targets = listOf("localhost:9000"))),
        )

    @Test
    fun `no jobs render to an empty string`() {
        assertThat(PrometheusScrapeJob.render(emptyList())).isEmpty()
    }

    @Test
    fun `every non-blank line is indented to the scrape_configs depth by default`() {
        val rendered = PrometheusScrapeJob.render(listOf(job("a"), job("b")))

        val lines = rendered.lines()
        assertThat(lines).isNotEmpty()
        assertThat(lines.filter { it.isNotEmpty() })
            .allMatch { it.startsWith(PrometheusScrapeJob.SCRAPE_CONFIGS_INDENT) }
        // Each job's entry starts a list item at that depth.
        assertThat(rendered).contains("${PrometheusScrapeJob.SCRAPE_CONFIGS_INDENT}- job_name: \"a\"")
        assertThat(rendered).contains("${PrometheusScrapeJob.SCRAPE_CONFIGS_INDENT}- job_name: \"b\"")
    }

    @Test
    fun `a custom indent is applied instead of the default`() {
        val rendered = PrometheusScrapeJob.render(listOf(job("a")), indent = "  ")

        assertThat(rendered).startsWith("  - job_name: \"a\"")
        assertThat(rendered.lines().filter { it.isNotEmpty() }).allMatch { it.startsWith("  ") }
    }

    @Test
    fun `the rendered YAML carries no trailing whitespace`() {
        val rendered = PrometheusScrapeJob.render(listOf(job("a")))

        assertThat(rendered).isEqualTo(rendered.trimEnd())
    }
}
