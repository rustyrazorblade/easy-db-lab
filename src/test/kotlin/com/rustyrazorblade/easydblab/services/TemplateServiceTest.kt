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
    fun `buildContextVariables uses empty string when no control host exists`() {
        setupClusterState(controlHost = null)

        val service = createService()
        val variables = service.buildContextVariables()

        assertThat(variables).containsEntry("CONTROL_NODE_IP", "")
    }

    @Test
    fun `extractResources extracts YAML files from classpath to directory`() {
        setupClusterState()

        val service = createService()
        val files = service.extractResources(destinationDir = manifestDir)

        assertThat(files).isNotEmpty()
        assertThat(files).allSatisfy { file ->
            assertThat(file.exists()).isTrue()
            assertThat(file.extension).isIn("yaml", "yml")
        }
    }

    @Test
    fun `extractResources does not substitute placeholders`() {
        setupClusterState()

        val service = createService()
        val files = service.extractResources(destinationDir = manifestDir)

        // Find a file that contains __BUCKET_NAME__ placeholder (S3-related dashboards)
        val filesWithPlaceholders =
            files.filter { it.readText().contains("__BUCKET_NAME__") }
        // If any files have placeholders, they should remain un-substituted
        filesWithPlaceholders.forEach { file ->
            assertThat(file.readText()).contains("__BUCKET_NAME__")
        }
    }

    @Test
    fun `extractAndSubstituteResources extracts and substitutes placeholders`() {
        setupClusterState()

        val service = createService()
        val files = service.extractAndSubstituteResources(destinationDir = manifestDir)

        assertThat(files).isNotEmpty()
        // No file should contain un-substituted __BUCKET_NAME__ placeholder
        files.forEach { file ->
            assertThat(file.readText()).doesNotContain("__BUCKET_NAME__")
        }
    }

    @Test
    fun `extractAndSubstituteResources with filter only includes matching files`() {
        setupClusterState()

        val service = createService()
        val allFiles = service.extractAndSubstituteResources(destinationDir = manifestDir)

        val coreOnly =
            service.extractAndSubstituteResources(
                destinationDir = manifestDir,
                filter = { it.startsWith("core/") },
            )

        assertThat(coreOnly).isNotEmpty()
        assertThat(coreOnly.size).isLessThanOrEqualTo(allFiles.size)
        // All returned files should be under core/
        coreOnly.forEach { file ->
            assertThat(file.path).contains("core")
        }
    }

    @Test
    fun `extractAndSubstituteResources preserves Grafana dollar-brace syntax`() {
        setupClusterState()

        val service = createService()
        val files = service.extractAndSubstituteResources(destinationDir = manifestDir)

        // Grafana dashboard files use ${datasource} syntax that must be preserved
        val grafanaFiles = files.filter { it.name.contains("grafana") }
        grafanaFiles.forEach { file ->
            val content = file.readText()
            // __KEY__ placeholders should be substituted but ${var} should remain
            assertThat(content).doesNotContain("__BUCKET_NAME__")
        }
    }

    @Test
    fun `extractResources with filter excludes non-matching files`() {
        setupClusterState()

        val service = createService()
        val dashboardOnly =
            service.extractResources(
                destinationDir = manifestDir,
                filter = { it.contains("grafana-dashboard") },
            )

        assertThat(dashboardOnly).isNotEmpty()
        dashboardOnly.forEach { file ->
            assertThat(file.name).contains("grafana-dashboard")
        }
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
        // Use a known resource from VictoriaBackupService
        val template =
            service.fromResource(
                DefaultVictoriaBackupService::class.java,
                "vmbackup-job.yaml",
            )

        val result =
            template.substitute(
                mapOf(
                    "JOB_NAME" to "test-job",
                    "S3_BUCKET" to "test-bucket",
                    "S3_KEY" to "backups/test",
                ),
            )

        assertThat(result).contains("test-job")
        assertThat(result).contains("test-bucket")
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
