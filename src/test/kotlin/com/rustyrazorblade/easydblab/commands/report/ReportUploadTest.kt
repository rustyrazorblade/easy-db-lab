package com.rustyrazorblade.easydblab.commands.report

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InfrastructureStatus
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.documents.DefaultReportArchiveService
import com.rustyrazorblade.easydblab.services.documents.InMemoryConditionalObjectStore
import com.rustyrazorblade.easydblab.services.documents.ReportArchiveService
import com.rustyrazorblade.easydblab.services.documents.ReportRejectedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import picocli.CommandLine
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Tests for `report upload <dir>`: it takes one directory, reads the experiment from the
 * workspace state, works on a cluster that is down (only the state file and S3 are needed), and
 * reports the upload in one typed event.
 *
 * S3 is the in-memory conditional store; the archive service, the zip and the pointer are the real
 * code.
 */
class ReportUploadTest : BaseKoinTest() {
    private val store = InMemoryConditionalObjectStore()
    private val stateManager = mock<ClusterStateManager>()
    private val events = mutableListOf<Event>()

    private val downCluster =
        ClusterState(
            name = "lab",
            versions = mutableMapOf(),
            clusterId = "abc",
            s3Bucket = "acct",
            initConfig = InitConfig(tenant = "acme", experiment = "e1"),
            infrastructureStatus = InfrastructureStatus.DOWN,
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { stateManager }
                single<ReportArchiveService> {
                    DefaultReportArchiveService(store, Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC))
                }
            },
        )

    @BeforeEach
    fun setup() {
        whenever(stateManager.load()).thenReturn(downCluster)
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
    }

    private fun parsed(vararg args: String): ReportUpload = ReportUpload().also { CommandLine(it).parseArgs(*args) }

    private fun reportDir(): File =
        File(tempDir, "results").also { dir ->
            dir.mkdirs()
            File(dir, "results.md").writeText("# Results")
            File(dir, "graph.png").writeText("png")
        }

    @Test
    fun `a cluster that is down uploads a directory into its experiment's folder`() {
        val dir = reportDir()

        parsed(dir.path).execute()

        val uploaded = events.filterIsInstance<Event.Report.Uploaded>().single()
        assertThat(uploaded.zipKey).isEqualTo("reports/acme/e1/report-20261014-120000.zip")
        assertThat(uploaded.unpackedPrefix).isEqualTo("reports/acme/e1/report-20261014-120000/")
        assertThat(uploaded.pointerAdvanced).isTrue()
        assertThat(uploaded.files).containsExactly("graph.png", "results.md")
        assertThat(store.keys()).contains("reports/acme/e1/report-20261014-120000.zip", "reports/acme/e1/index.html")
    }

    @Test
    fun `the event says when a newer upload is already shown`() {
        val event = Event.Report.Uploaded("reports/acme/e1/report-1.zip", "reports/acme/e1/report-1/", false, listOf("a.md"))

        assertThat(event.toDisplayString()).contains("reports/acme/e1/report-1.zip", "newer upload")
        assertThat(event.copy(pointerAdvanced = true).toDisplayString()).doesNotContain("newer upload")
    }

    @Test
    fun `the file list form fails, says it takes one directory, and uploads nothing`() {
        val a = File(tempDir, "a.md").also { it.writeText("a") }
        val b = File(tempDir, "b.md").also { it.writeText("b") }

        assertThatThrownBy { parsed(a.path, b.path).execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("one directory")
        assertThat(store.keys()).isEmpty()
    }

    @Test
    fun `a missing directory is named and nothing is uploaded`() {
        val missing = File(tempDir, "missing")

        assertThatThrownBy { parsed(missing.path).execute() }
            .isInstanceOf(ReportRejectedException::class.java)
            .hasMessageContaining("${missing.path}/")
        assertThat(store.keys()).isEmpty()
    }

    @Test
    fun `a directory with an unservable name names it and uploads nothing`() {
        val dir = reportDir()
        File(dir, "my notes.md").writeText("x")

        assertThatThrownBy { parsed(dir.path).execute() }
            .isInstanceOf(ReportRejectedException::class.java)
            .hasMessageContaining("my notes.md")
        assertThat(store.keys()).isEmpty()
    }
}
