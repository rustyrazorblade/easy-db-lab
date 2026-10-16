package com.rustyrazorblade.easydblab.commands.observability

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ExperimentKey
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.export.ExportSelector
import com.rustyrazorblade.easydblab.export.ExportSelectorRefused
import com.rustyrazorblade.easydblab.export.ExportStackFiles
import com.rustyrazorblade.easydblab.export.ExportZipWriter
import com.rustyrazorblade.easydblab.export.FakeExportStore
import com.rustyrazorblade.easydblab.export.TenantExporter
import com.rustyrazorblade.easydblab.export.query.QueryExporter
import com.rustyrazorblade.easydblab.export.query.WorkspaceCluster
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.localstack.DashboardTreeSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import picocli.CommandLine
import java.io.File

/**
 * `observability export <path>` through the command line: exactly one kind of selector, a new path,
 * the experiment's tenant reported with its reason, and a tenant export written through the real
 * exporter over an account bucket held in memory.
 */
class ObservabilityExportTest : BaseKoinTest() {
    private companion object {
        const val X = "x-0f8fad5b-d9cb-469f-a165-70867728950e"
        const val W = "w-7c9e6679-7425-40de-944b-e07fc1f90ae7"
    }

    private val store = FakeExportStore()
    private val events = mutableListOf<Event>()
    private val stateManager = mock<ClusterStateManager>()
    private val queryExporter = mock<QueryExporter>()

    private val dashboards =
        object : DashboardTreeSource {
            override val origin: String = "packaged dashboards"

            override fun render(context: DashboardInstallContext): Map<String, String> = mapOf("home/home.json" to "{}")
        }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { stateManager }
                single { TenantExporter({ store }, ExportStackFiles(dashboards, "16"), ExportZipWriter(), { Long.MAX_VALUE }, get()) }
                single { queryExporter }
            },
        )

    @BeforeEach
    fun listen() {
        whenever(stateManager.exists()).thenReturn(false)
        get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
    }

    private fun export(vararg args: String) = ObservabilityExport().also { CommandLine(it).parseArgs(*args) }.execute()

    private fun zip(name: String = "out.zip") = File(tempDir, name).path

    @Test
    fun `no selector is refused, naming the three choices`() {
        assertThatThrownBy { export(zip()) }
            .isInstanceOf(ExportSelectorRefused::class.java)
            .hasMessageContaining("--tenant")
            .hasMessageContaining("--cluster")
            .hasMessageContaining("--experiment")
    }

    @Test
    fun `a cluster and an experiment together are refused, naming the three choices`() {
        assertThatThrownBy { export(zip(), "--cluster", "x-1", "--experiment", "e") }
            .isInstanceOf(ExportSelectorRefused::class.java)
            .hasMessageContaining("--tenant")
            .hasMessageContaining("--cluster")
            .hasMessageContaining("--experiment")
    }

    @Test
    fun `a missing path is refused, naming the path argument`() {
        assertThatThrownBy { CommandLine(ObservabilityExport()).parseArgs("--tenant", "acme") }
            .isInstanceOf(CommandLine.MissingParameterException::class.java)
            .hasMessageContaining("PATH")
    }

    @Test
    fun `an existing path is refused and left unchanged`() {
        val existing = File(tempDir, "out.zip").apply { writeText("mine") }

        assertThatThrownBy { export(existing.path, "--tenant", "acme") }.hasMessageContaining("already exists")
        assertThat(existing.readText()).isEqualTo("mine")
    }

    @Test
    fun `an experiment outside a workspace uses the default tenant and says so`() {
        export(zip(), "--experiment", "e")

        val chosen = events.filterIsInstance<Event.Export.TenantChosen>().single()
        assertThat(chosen.tenant).isEqualTo("default")
        verify(queryExporter).export(any(), eq(ExportSelector.Experiment(ExperimentKey("default", "e"))), isNull())
        assertThat(chosen.toDisplayString()).contains("tenant default", "not a cluster workspace")
    }

    @Test
    fun `an experiment inside a workspace uses the workspace's tenant and says so`() {
        val state = ClusterState(name = "lab", versions = mutableMapOf(), initConfig = InitConfig(tenant = "acme"))
        whenever(stateManager.exists()).thenReturn(true)
        whenever(stateManager.load()).thenReturn(state)

        export(zip(), "--experiment", "e")

        val chosen = events.filterIsInstance<Event.Export.TenantChosen>().single()
        assertThat(chosen.tenant).isEqualTo("acme")
        verify(
            queryExporter,
        ).export(any(), eq(ExportSelector.Experiment(ExperimentKey("acme", "e"))), eq(WorkspaceCluster("lab-${state.clusterId}", "acme")))
        assertThat(chosen.toDisplayString()).contains("tenant acme", "workspace")
    }

    @Test
    fun `clusters are exported by query, outside a workspace with no workspace cluster`() {
        export(zip(), "--cluster", X, "--cluster", W, "--cluster", X)

        verify(queryExporter).export(any(), eq(ExportSelector.Clusters(listOf(X, W))), isNull())
    }

    @Test
    fun `a cluster with a quote is refused with an event naming it, and nothing is exported`() {
        val injected = "x-1\"} or vector(1) #"

        assertThatThrownBy { export(zip(), "--cluster", X, "--cluster", injected) }.isInstanceOf(CommandFailedException::class.java)

        assertThat(events.filterIsInstance<Event.Export.InvalidCluster>().single().clusters).containsExactly(injected)
        assertThat(events.single().toDisplayString()).contains(injected, "--cluster")
        verifyNoInteractions(queryExporter)
        assertThat(File(zip())).doesNotExist()
    }

    @Test
    fun `a tenant export writes the zip at the path, outside a workspace`() {
        store.put("tempo/acme/t1/meta.json", """{"blockID":"t1"}""")
        store.put("tempo/acme/t1/data.parquet", "spans")

        export(zip("acme.zip"), "--tenant", "acme")

        assertThat(File(tempDir, "acme.zip")).isFile()
        assertThat(events.filterIsInstance<Event.Export.Completed>().single().tenant).isEqualTo("acme")
    }
}
