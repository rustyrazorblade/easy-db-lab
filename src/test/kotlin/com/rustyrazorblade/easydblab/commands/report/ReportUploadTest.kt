package com.rustyrazorblade.easydblab.commands.report

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ClusterStoragePath
import com.rustyrazorblade.easydblab.configuration.InfrastructureStatus
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.InMemoryObjectStore
import com.rustyrazorblade.easydblab.services.ObjectStore
import com.rustyrazorblade.easydblab.services.documents.DefaultTestDocumentService
import com.rustyrazorblade.easydblab.services.documents.DocumentsRejectedException
import com.rustyrazorblade.easydblab.services.documents.TestDocumentService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Tests for `report upload`: the name rule stops the whole upload, and a cluster that is down (no
 * hosts, infrastructure DOWN) still takes documents, because only the state file and S3 are needed.
 *
 * Storage is an in-memory [ObjectStore]; the key layout and the index are the real code's.
 */
class ReportUploadTest : BaseKoinTest() {
    private val objectStore = InMemoryObjectStore()
    private val stateManager = mock<ClusterStateManager>()
    private val events = mutableListOf<Event>()

    private val downCluster =
        ClusterState(
            name = "lab",
            versions = mutableMapOf(),
            clusterId = "abc",
            s3Bucket = "acct",
            initConfig = InitConfig(tenant = "acme"),
            infrastructureStatus = InfrastructureStatus.DOWN,
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { stateManager }
                single<ObjectStore> { objectStore }
                single<TestDocumentService> { DefaultTestDocumentService(objectStore) }
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

    private fun command(vararg files: File) = ReportUpload().also { it.files = files.toList() }

    @Test
    fun `rejected files are named and nothing is uploaded`() {
        val good = File(tempDir, "results.md").also { it.writeText("ok") }
        val bad = File(tempDir, "my notes.md").also { it.writeText("x") }
        val png = File(tempDir, "graph.png").also { it.writeText("x") }

        assertThatThrownBy { command(good, bad, png).execute() }
            .isInstanceOf(DocumentsRejectedException::class.java)
            .hasMessageContaining("my notes.md")
            .hasMessageContaining("graph.png")
            .satisfies({
                assertThat(
                    (it as DocumentsRejectedException).rejected.map { r ->
                        r.name
                    },
                ).containsExactly("my notes.md", "graph.png")
            })
        assertThat(objectStore.listFiles(ClusterStoragePath.root("acct"), recursive = true)).isEmpty()
    }

    @Test
    fun `a cluster that is down takes documents into its own folder`() {
        val results = File(tempDir, "results.md").also { it.writeText("# Results") }

        command(results).execute()

        val stored = objectStore.listFiles(ClusterStoragePath.root("acct"), recursive = true).map { it.path.getKey() }
        assertThat(stored)
            .containsExactlyInAnyOrder(
                "reports/acme/lab-abc/results.md",
                "reports/acme/lab-abc/results.html",
                "reports/acme/lab-abc/index.html",
            )
        val uploaded = events.filterIsInstance<Event.Report.DocumentsUploaded>().single()
        assertThat(
            uploaded.documents,
        ).containsExactly(Event.Report.StoredDocument("results.md", "mem://acct/reports/acme/lab-abc/results.md"))
        assertThat(uploaded.indexUri).isEqualTo("mem://acct/reports/acme/lab-abc/index.html")
    }
}
