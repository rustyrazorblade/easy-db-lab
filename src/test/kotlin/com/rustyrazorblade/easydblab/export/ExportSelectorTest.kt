package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.configuration.ExperimentKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * `observability export` takes exactly one kind of selector. `--tenant` alone is a tenant export;
 * beside `--experiment` it names the experiment's tenant, which otherwise is the workspace's, or
 * `default` outside one, and the command says which and why.
 */
class ExportSelectorTest {
    private companion object {
        const val X = "x-0f8fad5b-d9cb-469f-a165-70867728950e"
        const val Y = "unthrottled_cursor-7c9e6679-7425-40de-944b-e07fc1f90ae7"
    }

    private fun select(
        tenant: String? = null,
        clusters: List<String> = emptyList(),
        experiment: String? = null,
        workspaceTenant: String? = null,
    ): ExportSelection = ExportSelection.of(ExportSelectorChoice(tenant, clusters, experiment), workspaceTenant)

    private fun assertRefusedNamingTheThreeChoices(block: () -> Unit) {
        assertThatThrownBy(block)
            .isInstanceOf(ExportSelectorRefused::class.java)
            .hasMessageContaining("--tenant")
            .hasMessageContaining("--cluster")
            .hasMessageContaining("--experiment")
    }

    @Test
    fun `no selector is refused, naming the three choices`() {
        assertRefusedNamingTheThreeChoices { select() }
    }

    @Test
    fun `clusters and an experiment together are refused, naming the three choices`() {
        assertRefusedNamingTheThreeChoices { select(clusters = listOf("x-1"), experiment = "e") }
    }

    @Test
    fun `a tenant beside clusters is two kinds of selector and is refused`() {
        assertRefusedNamingTheThreeChoices { select(tenant = "acme", clusters = listOf("x-1")) }
    }

    @Test
    fun `a tenant alone is a tenant export, with no tenant to report`() {
        val selection = select(tenant = "acme", workspaceTenant = "default")

        assertThat(selection.selector).isEqualTo(ExportSelector.Tenant("acme"))
        assertThat(selection.tenantChoice).isNull()
    }

    @Test
    fun `repeated clusters are one cluster selector, each named once`() {
        assertThat(select(clusters = listOf(X, Y, X)).selector)
            .isEqualTo(ExportSelector.Clusters(listOf(X, Y)))
    }

    @Test
    fun `an experiment outside a workspace with no tenant uses default and says it is the default`() {
        val selection = select(experiment = "e")

        assertThat(selection.selector).isEqualTo(ExportSelector.Experiment(ExperimentKey("default", "e")))
        assertThat(selection.tenantChoice).isEqualTo(TenantChoice("default", TenantChoice.Source.DEFAULT))
    }

    @Test
    fun `an experiment inside a workspace with no tenant uses the workspace's tenant and says so`() {
        val selection = select(experiment = "e", workspaceTenant = "acme")

        assertThat(selection.selector).isEqualTo(ExportSelector.Experiment(ExperimentKey("acme", "e")))
        assertThat(selection.tenantChoice).isEqualTo(TenantChoice("acme", TenantChoice.Source.WORKSPACE))
    }

    @Test
    fun `an experiment's tenant option wins over the workspace's`() {
        val selection = select(experiment = "e", tenant = "beta", workspaceTenant = "acme")

        assertThat(selection.selector).isEqualTo(ExportSelector.Experiment(ExperimentKey("beta", "e")))
        assertThat(selection.tenantChoice).isEqualTo(TenantChoice("beta", TenantChoice.Source.OPTION))
    }

    @Test
    fun `a tenant that breaks the tenant rule is refused`() {
        assertThatThrownBy { select(tenant = "Not A Tenant") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Not A Tenant")
    }

    @Test
    fun `a blank cluster is refused`() {
        assertThatThrownBy { select(clusters = listOf(" ")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--cluster")
    }

    /** A --cluster value goes into PromQL, LogQL and TraceQL selectors: only a cluster's own id may. */
    @Test
    fun `a cluster that is not a name and a uuid is refused, naming each such value`() {
        val injected = "x-1\"} or vector(1) #"

        assertThatThrownBy { select(clusters = listOf(X, injected, "x-1")) }
            .isInstanceOfSatisfying(ClusterIdRefused::class.java) { assertThat(it.clusters).containsExactly(injected, "x-1") }
            .hasMessageContaining(injected)
    }

    @Test
    fun `an upper-case or truncated id is not a cluster's id`() {
        assertThatThrownBy { select(clusters = listOf(X.uppercase())) }.isInstanceOf(ClusterIdRefused::class.java)
        assertThatThrownBy { select(clusters = listOf(X.dropLast(1))) }.isInstanceOf(ClusterIdRefused::class.java)
    }
}
