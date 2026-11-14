package com.rustyrazorblade.easydblab.commands.spark

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.CommandLineParser
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ClusterStoragePath
import com.rustyrazorblade.easydblab.configuration.EMRClusterState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.DefaultHelpTopicService
import com.rustyrazorblade.easydblab.services.DefaultKitCommandScanner
import com.rustyrazorblade.easydblab.services.HelpTopicService
import com.rustyrazorblade.easydblab.services.InMemoryObjectStore
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitCommandScanner
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import com.rustyrazorblade.easydblab.services.LokiQueryService
import com.rustyrazorblade.easydblab.services.ObjectStore
import com.rustyrazorblade.easydblab.services.SparkService
import com.rustyrazorblade.easydblab.services.WorkspaceKitScanner
import com.rustyrazorblade.easydblab.services.aws.EMRSparkService
import com.rustyrazorblade.easydblab.services.aws.S3Uri
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.emr.model.DescribeStepRequest
import software.amazon.awssdk.services.emr.model.DescribeStepResponse
import software.amazon.awssdk.services.emr.model.Step
import software.amazon.awssdk.services.emr.model.StepState
import software.amazon.awssdk.services.emr.model.StepStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.time.Duration
import java.util.zip.GZIPOutputStream

/**
 * Tests `spark status`, and `--logs` in particular: a failed `spark submit --wait` whose final
 * stderr arrives late tells the user to run `spark status --step-id <id> --logs`, so that exact
 * command must parse and must print the step's stderr from S3.
 *
 * Only the external boundary is mocked: the EMR client and the object store. The Spark service
 * is the real [EMRSparkService], reading a real saved cluster state.
 */
class SparkStatusTest : BaseKoinTest() {
    private lateinit var mockEmrClient: EmrClient
    private lateinit var objectStore: InMemoryObjectStore
    private val stdout = ByteArrayOutputStream()
    private val originalOut = System.out

    private val clusterId = "j-STATUSTEST"
    private val stepId = "s-STATUSSTEP"

    private val clusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = "test-bucket",
            emrCluster =
                EMRClusterState(
                    clusterId = clusterId,
                    clusterName = "test-emr",
                    masterPublicDns = "master.example.com",
                    state = "WAITING",
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<EmrClient> { mockEmrClient }
                single<ObjectStore> { objectStore }
                single { ClusterStateManager(File(tempDir, "state.json")).also { it.save(clusterState) } }
                single<LokiQueryService> { mock() }
                single<SparkService> {
                    EMRSparkService(get(), get(), get(), get(), get(), finalLogPollInterval = Duration.ZERO, logsDir = tempDir.toPath())
                }
                single { WorkspaceKitScanner(get()) }
                single { KitSourcesProvider(get()) }
                single { InstallTemplateResolver(get(), get()) }
                single<KitCommandScanner> { DefaultKitCommandScanner() }
                single<HelpTopicService> { DefaultHelpTopicService() }
            },
        )

    @BeforeEach
    fun setup() {
        mockEmrClient = mock()
        objectStore = InMemoryObjectStore()
        whenever(mockEmrClient.describeStep(any<DescribeStepRequest>())).thenReturn(failedStep())
        System.setOut(PrintStream(stdout))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
        stdout.reset()
    }

    @Test
    fun `the command the late-stderr notice prints parses as spark status for that step with logs`() {
        val notice = Event.Emr.StepStderrNotFinal(stepId, S3Uri.uri(stderrPath()), waitedSeconds = 420).toDisplayString()
        val printed = notice.lines().single { it.startsWith("Fetch it later with: ") }.substringAfter("Fetch it later with: ")
        val args = printed.split(" ")
        assertThat(args.first()).isEqualTo("easy-db-lab")

        val parsed = CommandLineParser().commandLine.parseArgs(*args.drop(1).toTypedArray())

        val status =
            parsed
                .subcommand()
                .subcommand()
                .commandSpec()
                .userObject()
        assertThat(status).isInstanceOf(SparkStatus::class.java)
        assertThat((status as SparkStatus).stepId).isEqualTo(stepId)
        assertThat(status.logs).isTrue()
    }

    @Test
    fun `status with logs prints the step's stderr downloaded from S3`() {
        val gzipped = File(tempDir, "stderr.gz")
        GZIPOutputStream(gzipped.outputStream()).use {
            it.write("Exception in thread \"main\" java.lang.IllegalStateException: keyspace missing\n".toByteArray())
        }
        objectStore.uploadFile(gzipped, stderrPath(), showProgress = false)

        SparkStatus()
            .apply {
                stepId = this@SparkStatusTest.stepId
                logs = true
            }.execute()

        assertThat(stdout.toString()).contains("java.lang.IllegalStateException: keyspace missing")
    }

    @Test
    fun `status with logs says the stderr is not in S3 yet and names its path`() {
        val events = recordEvents()

        SparkStatus()
            .apply {
                stepId = this@SparkStatusTest.stepId
                logs = true
            }.execute()

        val notUploaded = events.filterIsInstance<Event.Emr.StepStderrNotUploaded>().single()
        assertThat(notUploaded.stepId).isEqualTo(stepId)
        assertThat(notUploaded.s3Uri).isEqualTo(S3Uri.uri(stderrPath()))
        assertThat(notUploaded.toDisplayString()).contains(S3Uri.uri(stderrPath()))
    }

    private fun stderrPath() =
        ClusterStoragePath
            .from(clusterState)
            .emrLogs()
            .resolve(clusterId)
            .resolve("steps")
            .resolve(stepId)
            .resolve(SparkService.LogType.STDERR.filename)

    private fun failedStep() =
        DescribeStepResponse
            .builder()
            .step(
                Step
                    .builder()
                    .id(stepId)
                    .name("qa-connector-writer")
                    .status(StepStatus.builder().state(StepState.FAILED).build())
                    .build(),
            ).build()

    private fun recordEvents(): List<Event> {
        val events = mutableListOf<Event>()
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events.add(envelope.event)
                }

                override fun close() = Unit
            },
        )
        return events
    }
}
