package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Test suite for TemplateService and TemplateService.Template.
 *
 * Verifies that template placeholders in YAML files are correctly replaced
 * with runtime values from ClusterState.
 */
class TemplateServiceTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager

    @TempDir
    lateinit var manifestDir: File

    private val testControlHost =
        ClusterHost(
            publicIp = "54.123.45.67",
            privateIp = "10.0.1.100",
            alias = "control0",
            availabilityZone = "us-east-1a",
            instanceId = "i-test123",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        mockClusterStateManager = it
                    }
                }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockClusterStateManager = getKoin().get()
    }

    private fun createService() = TemplateService(mockClusterStateManager, getKoin().get())

    private fun setupClusterState(
        bucketName: String? = "my-test-bucket",
        region: String = "us-east-1",
        clusterName: String = "test-cluster",
        controlHost: ClusterHost? = testControlHost,
        dataBucket: String = "",
    ) {
        val state =
            ClusterState(
                name = clusterName,
                versions = mutableMapOf(),
                hosts =
                    if (controlHost != null) {
                        mutableMapOf(ServerType.Control to listOf(controlHost))
                    } else {
                        mutableMapOf()
                    },
                s3Bucket = bucketName,
                dataBucket = dataBucket,
                initConfig = InitConfig(region = region, name = clusterName),
            )
        whenever(mockClusterStateManager.load()).thenReturn(state)
    }

    @Test
    fun `buildContextVariables returns correct map from cluster state`() {
        setupClusterState()

        val service = createService()
        val variables = service.buildContextVariables()

        assertThat(variables).containsEntry("BUCKET_NAME", "my-test-bucket")
        assertThat(variables).containsEntry("AWS_REGION", "us-east-1")
        assertThat(variables).containsEntry("CLUSTER_NAME", "test-cluster")
        assertThat(variables).containsEntry("CONTROL_NODE_IP", "10.0.1.100")
    }

    @Test
    fun `buildContextVariables uses empty string when s3Bucket is null`() {
        setupClusterState(bucketName = null)

        val service = createService()
        val variables = service.buildContextVariables()

        assertThat(variables).containsEntry("BUCKET_NAME", "")
    }

    @Test
    fun `buildContextVariables uses dataBucket for BUCKET_NAME when set`() {
        setupClusterState(dataBucket = "easy-db-lab-data-test-id")

        val service = createService()
        val variables = service.buildContextVariables()

        assertThat(variables).containsEntry("BUCKET_NAME", "easy-db-lab-data-test-id")
    }

    @Test
    fun `buildContextVariables falls back to s3Bucket when dataBucket is blank`() {
        setupClusterState(bucketName = "my-account-bucket", dataBucket = "")

        val service = createService()
        val variables = service.buildContextVariables()

        assertThat(variables).containsEntry("BUCKET_NAME", "my-account-bucket")
    }

    @Test
    fun `buildContextVariables uses empty string when no control host exists`() {
        setupClusterState(controlHost = null)

        val service = createService()
        val variables = service.buildContextVariables()

        assertThat(variables).containsEntry("CONTROL_NODE_IP", "")
    }

    // ========== Template class tests ==========

    @Test
    fun `Template substitute replaces placeholders with context variables`() {
        val template =
            TemplateService.Template(
                "bucket: __BUCKET_NAME__, region: __AWS_REGION__",
                mapOf("BUCKET_NAME" to "my-bucket", "AWS_REGION" to "us-west-2"),
            )

        assertThat(template.substitute()).isEqualTo("bucket: my-bucket, region: us-west-2")
    }

    @Test
    fun `Template substitute with extraVars merges and overrides context variables`() {
        val template =
            TemplateService.Template(
                "region: __AWS_REGION__, job: __JOB_NAME__",
                mapOf("AWS_REGION" to "us-east-1"),
            )

        val result = template.substitute(mapOf("JOB_NAME" to "backup-1", "AWS_REGION" to "eu-west-1"))

        assertThat(result).isEqualTo("region: eu-west-1, job: backup-1")
    }

    @Test
    fun `Template substitute with no matching placeholders returns original`() {
        val template = TemplateService.Template("no placeholders", emptyMap())

        assertThat(template.substitute()).isEqualTo("no placeholders")
    }

    @Test
    fun `Template file constructor reads file content`() {
        val file = File(manifestDir, "template.yaml")
        file.writeText("bucket: __BUCKET_NAME__")

        val template = TemplateService.Template(file, mapOf("BUCKET_NAME" to "test-bucket"))

        assertThat(template.substitute()).isEqualTo("bucket: test-bucket")
    }

    @Test
    fun `fromResource loads classpath resource correctly`() {
        setupClusterState()

        val service = createService()
        // Use a known resource — OTel collector config contains __KEY__ variables
        val template =
            service.fromResource(
                com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder::class.java,
                "otel-collector-config.yaml",
            )

        val result = template.substitute()

        // The OTel config should contain standard collector structure
        assertThat(result).contains("receivers:")
        assertThat(result).contains("exporters:")
    }

    @Test
    fun `fromString creates template with context variables`() {
        setupClusterState()

        val service = createService()
        val template = service.fromString("bucket: __BUCKET_NAME__")

        assertThat(template.substitute()).isEqualTo("bucket: my-test-bucket")
    }

    @Test
    fun `fromFile creates template from file with context variables`() {
        setupClusterState()

        val file = File(manifestDir, "test.yaml")
        file.writeText("region: __AWS_REGION__")

        val service = createService()
        val template = service.fromFile(file)

        assertThat(template.substitute()).isEqualTo("region: us-east-1")
    }
}
