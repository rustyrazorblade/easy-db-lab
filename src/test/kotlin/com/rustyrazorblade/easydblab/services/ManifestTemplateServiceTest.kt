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
 * Test suite for ManifestTemplateService.
 *
 * Verifies that template placeholders in YAML files are correctly replaced
 * with runtime values from ClusterState.
 */
class ManifestTemplateServiceTest : BaseKoinTest() {
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

    private fun createService() = DefaultManifestTemplateService(mockClusterStateManager, getKoin().get())

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
    fun `buildVariables returns correct map from cluster state`() {
        setupClusterState()

        val service = createService()
        val variables = service.buildVariables()

        assertThat(variables).containsEntry("BUCKET_NAME", "my-test-bucket")
        assertThat(variables).containsEntry("AWS_REGION", "us-east-1")
        assertThat(variables).containsEntry("CLUSTER_NAME", "test-cluster")
        assertThat(variables).containsEntry("CONTROL_NODE_IP", "10.0.1.100")
    }

    @Test
    fun `buildVariables uses empty string when s3Bucket is null`() {
        setupClusterState(bucketName = null)

        val service = createService()
        val variables = service.buildVariables()

        assertThat(variables).containsEntry("BUCKET_NAME", "")
    }

    @Test
    fun `buildVariables uses empty string when no control host exists`() {
        setupClusterState(controlHost = null)

        val service = createService()
        val variables = service.buildVariables()

        assertThat(variables).containsEntry("CONTROL_NODE_IP", "")
    }

    @Test
    fun `replaceAll substitutes BUCKET_NAME in yaml files`() {
        setupClusterState()

        val yamlFile = File(manifestDir, "dashboard.yaml")
        yamlFile.writeText("query: __BUCKET_NAME__")

        val service = createService()
        service.replaceAll(manifestDir)

        assertThat(yamlFile.readText()).isEqualTo("query: my-test-bucket")
    }

    @Test
    fun `replaceAll substitutes multiple placeholders`() {
        setupClusterState()

        val yamlFile = File(manifestDir, "config.yaml")
        yamlFile.writeText(
            """
            bucket: __BUCKET_NAME__
            region: __AWS_REGION__
            cluster: __CLUSTER_NAME__
            control: __CONTROL_NODE_IP__
            """.trimIndent(),
        )

        val service = createService()
        service.replaceAll(manifestDir)

        val result = yamlFile.readText()
        assertThat(result).contains("bucket: my-test-bucket")
        assertThat(result).contains("region: us-east-1")
        assertThat(result).contains("cluster: test-cluster")
        assertThat(result).contains("control: 10.0.1.100")
    }

    @Test
    fun `replaceAll does not modify files without placeholders`() {
        setupClusterState()

        val originalContent = "no placeholders here"
        val yamlFile = File(manifestDir, "static.yaml")
        yamlFile.writeText(originalContent)

        val service = createService()
        service.replaceAll(manifestDir)

        assertThat(yamlFile.readText()).isEqualTo(originalContent)
    }

    @Test
    fun `replaceAll ignores non-yaml files`() {
        setupClusterState()

        val txtFile = File(manifestDir, "notes.txt")
        txtFile.writeText("__BUCKET_NAME__")

        val service = createService()
        service.replaceAll(manifestDir)

        assertThat(txtFile.readText()).isEqualTo("__BUCKET_NAME__")
    }

    @Test
    fun `replaceAll processes yml extension`() {
        setupClusterState()

        val ymlFile = File(manifestDir, "dashboard.yml")
        ymlFile.writeText("bucket: __BUCKET_NAME__")

        val service = createService()
        service.replaceAll(manifestDir)

        assertThat(ymlFile.readText()).isEqualTo("bucket: my-test-bucket")
    }

    @Test
    fun `replaceAll preserves Grafana dollar-brace syntax`() {
        setupClusterState()

        val yamlFile = File(manifestDir, "grafana.yaml")
        yamlFile.writeText("""uid: "${'$'}{datasource}", bucket: __BUCKET_NAME__""")

        val service = createService()
        service.replaceAll(manifestDir)

        val result = yamlFile.readText()
        assertThat(result).contains("""uid: "${'$'}{datasource}"""")
        assertThat(result).contains("bucket: my-test-bucket")
    }

    @Test
    fun `replaceAll walks subdirectories`() {
        setupClusterState()

        val subDir = File(manifestDir, "core")
        subDir.mkdirs()
        val yamlFile = File(subDir, "dashboard.yaml")
        yamlFile.writeText("query: __BUCKET_NAME__")

        val service = createService()
        service.replaceAll(manifestDir)

        assertThat(yamlFile.readText()).isEqualTo("query: my-test-bucket")
    }
}
