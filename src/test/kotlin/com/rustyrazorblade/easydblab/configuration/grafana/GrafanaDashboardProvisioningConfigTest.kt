package com.rustyrazorblade.easydblab.configuration.grafana

import com.charleskorn.kaml.Yaml
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for the dashboard provisioning YAML.
 *
 * One file provider sweeps the whole copied tree and derives Grafana folders from its
 * directories. A second provider, or a `folder` on this one, would either file every dashboard
 * into one folder or double-read the tree, so the shape of the generated file is pinned here.
 */
class GrafanaDashboardProvisioningConfigTest {
    private val yaml = GrafanaDashboardProvisioningConfig.forDashboardTree().toYaml()

    private fun decoded(): GrafanaDashboardProvisioningConfig =
        Yaml.default.decodeFromString(GrafanaDashboardProvisioningConfig.serializer(), yaml)

    @Test
    fun `exactly one provider sweeps the dashboard tree root with folders from its directories`() {
        val provider = decoded().providers.single()

        assertThat(provider.name).isEqualTo("dashboards")
        assertThat(provider.options.path).isEqualTo(GRAFANA_DASHBOARD_ROOT)
        assertThat(provider.options.foldersFromFilesStructure).isTrue()
    }

    @Test
    fun `the provider names no folder so Grafana derives one per directory`() {
        // With foldersFromFilesStructure set, Grafana rejects a provider that also names a
        // folder; and a folder here would file every dashboard into that one folder anyway.
        assertThat(yaml).doesNotContain("folder:")
        assertThat(yaml).doesNotContain("folderUid")
    }

    @Test
    fun `provider settings Grafana reads are written out explicitly`() {
        assertThat(yaml).contains("apiVersion: 1")
        assertThat(yaml).contains("type: \"file\"")
        assertThat(yaml).contains("disableDeletion: false")
        assertThat(yaml).contains("updateIntervalSeconds: 10")
        assertThat(yaml).contains("allowUiUpdates: true")
    }
}
