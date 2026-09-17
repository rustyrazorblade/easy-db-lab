package com.rustyrazorblade.easydblab.configuration.grafana

import com.charleskorn.kaml.Yaml
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for the dashboard provisioning YAML generated from the discovered folders.
 *
 * A folder with no provider is a silent failure: its ConfigMaps mount, Grafana starts, and the
 * dashboards simply never appear. The generated file must therefore have exactly one provider per
 * folder, watching exactly the path that folder's dashboards mount under.
 */
class GrafanaDashboardProvisioningConfigTest {
    private val folders = listOf("cassandra", "infrastructure")

    private fun decoded(): GrafanaDashboardProvisioningConfig =
        Yaml.default.decodeFromString(
            GrafanaDashboardProvisioningConfig.serializer(),
            GrafanaDashboardProvisioningConfig.forFolders(folders).toYaml(),
        )

    @Test
    fun `one provider per folder watching that folder's provider path`() {
        val providers = decoded().providers

        assertThat(providers.map { it.name }).containsExactly("cassandra", "infrastructure")
        assertThat(providers).allSatisfy { provider ->
            assertThat(provider.folder).isEqualTo(provider.name)
            assertThat(provider.folderUid).isEqualTo(provider.name)
            assertThat(provider.options.path).isEqualTo(GrafanaDashboard.folderProviderPath(provider.name))
        }
    }

    @Test
    fun `no provider sweeps the root path`() {
        // Every dashboard sits in a folder, so a root provider would file nothing. With
        // foldersFromFilesStructure unset it would also be a second reader of nothing.
        assertThat(decoded().providers.map { it.options.path })
            .doesNotContain(GRAFANA_DASHBOARD_ROOT)
    }

    @Test
    fun `provider settings Grafana reads are written out explicitly`() {
        val yaml = GrafanaDashboardProvisioningConfig.forFolders(folders).toYaml()

        assertThat(yaml).contains("apiVersion: 1")
        assertThat(yaml).contains("type: \"file\"")
        assertThat(yaml).contains("disableDeletion: false")
        assertThat(yaml).contains("updateIntervalSeconds: 10")
        assertThat(yaml).contains("allowUiUpdates: true")
        assertThat(yaml).doesNotContain("foldersFromFilesStructure")
    }
}
