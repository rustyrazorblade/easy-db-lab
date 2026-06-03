package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.commands.kit.Install
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver.TemplateEntry
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class InstallFromTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val mockResolver: InstallTemplateResolver = mock()
    private lateinit var outputHandler: BufferedOutputHandler
    private lateinit var workingDir: File

    private val controlHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-control",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single { TemplateService(get(), get()) }
                single<InstallTemplateResolver> { mockResolver }
            },
        )

    @BeforeEach
    fun setup() {
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
        workingDir = getKoin().get<Context>().workingDirectory

        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "test-bucket",
                initConfig = InitConfig(region = "us-west-2", name = "test-cluster"),
                hosts =
                    mapOf(
                        ServerType.Control to listOf(controlHost),
                        ServerType.Cassandra to
                            listOf(
                                ClusterHost("54.2.0.0", "10.0.2.0", "db0", "us-west-2a", "i-db0"),
                                ClusterHost("54.2.0.1", "10.0.2.1", "db1", "us-west-2b", "i-db1"),
                            ),
                        ServerType.Stress to
                            listOf(
                                ClusterHost("54.3.0.0", "10.0.3.0", "app0", "us-west-2a", "i-app0"),
                            ),
                    ),
            )
        whenever(mockClusterStateManager.load()).thenReturn(state)
    }

    private fun adHocSource(vararg files: Pair<String, String>): InstallTemplateResolver.TemplateSource.Directory {
        val dir = File(tempDir, "templates").also { it.mkdirs() }
        files.forEach { (name, content) ->
            val file = File(dir, name)
            file.parentFile.mkdirs()
            file.writeText(content)
        }
        val source = InstallTemplateResolver.TemplateSource.Directory(dir)
        whenever(mockResolver.listTemplateFiles(source))
            .thenReturn(files.map { (name, content) -> TemplateEntry(name, content) })
        return source
    }

    @Test
    fun `renders template files substituting all standard variables`() {
        val source =
            adHocSource(
                "start.sh.template" to
                    """
                    #!/bin/bash
                    easy-db-lab platform create-pvs --workload __KIT_NAME__ --size __STORAGE_SIZE__
                    echo "DB nodes: __DB_NODE_COUNT__"
                    echo "Region: __REGION__"
                    """.trimIndent(),
            )

        Install().renderAndWrite(source = source, kitName = "mydb", storageSize = "100Gi")

        val rendered = File(workingDir, "mydb/start.sh").readText()
        assertThat(rendered).contains("--workload mydb")
        assertThat(rendered).contains("--size 100Gi")
        assertThat(rendered).contains("DB nodes: 2")
        assertThat(rendered).contains("Region: us-west-2")
        assertThat(rendered).doesNotContain("__KIT_NAME__")
        assertThat(rendered).doesNotContain("__DB_NODE_COUNT__")
    }

    @Test
    fun `removes dot-template suffix from output file name`() {
        val source = adHocSource("values.yaml.template" to "workload: __KIT_NAME__")

        Install().renderAndWrite(source = source, kitName = "mydb", storageSize = "50Gi")

        assertThat(File(workingDir, "mydb/values.yaml")).exists()
        assertThat(File(workingDir, "mydb/values.yaml.template")).doesNotExist()
    }

    @Test
    fun `emits ScaffoldComplete event after rendering`() {
        val source = adHocSource("README.md.template" to "# __KIT_NAME__")

        Install().renderAndWrite(source = source, kitName = "mydb", storageSize = "50Gi")

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Scaffold written to")
        assertThat(output).contains("mydb start")
    }

    @Test
    fun `emits UnresolvedVariables warning when template has unknown placeholders`() {
        val source = adHocSource("values.yaml.template" to "replicas: __REPLICAS__\nshards: __SHARDS__")

        Install().renderAndWrite(source = source, kitName = "mydb", storageSize = "50Gi")

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("unresolved template variables")
        assertThat(output).contains("REPLICAS")
        assertThat(output).contains("SHARDS")
    }

    @Test
    fun `does not emit UnresolvedVariables when all placeholders are resolved`() {
        val source = adHocSource("start.sh.template" to "echo __KIT_NAME__")

        Install().renderAndWrite(source = source, kitName = "mydb", storageSize = "50Gi")

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).doesNotContain("unresolved template variables")
    }

    @Test
    fun `file in bin subdir is rendered to correct subpath`() {
        val source = adHocSource("bin/start.sh.template" to "#!/bin/bash\necho start")

        Install().renderAndWrite(source = source, kitName = "mydb", storageSize = "50Gi")

        assertThat(File(workingDir, "mydb/bin/start.sh")).exists()
        assertThat(File(workingDir, "mydb/bin/start.sh")).hasContent("#!/bin/bash\necho start")
    }

    @Test
    fun `file rendered into bin subdir is executable`() {
        val source = adHocSource("bin/start.sh.template" to "#!/bin/bash")

        Install().renderAndWrite(source = source, kitName = "mydb", storageSize = "50Gi")

        assertThat(File(workingDir, "mydb/bin/start.sh").canExecute()).isTrue()
    }

    @Test
    fun `file outside bin subdir is not made executable`() {
        val source = adHocSource("README.md.template" to "# docs")

        Install().renderAndWrite(source = source, kitName = "mydb", storageSize = "50Gi")

        assertThat(File(workingDir, "mydb/README.md").canExecute()).isFalse()
    }

    @Test
    fun `verbatim file without template suffix is copied as-is without substitution`() {
        val jsonContent = """{"title": "__KIT_NAME__"}"""
        val source = adHocSource("dashboards/clickhouse.json" to jsonContent)

        Install().renderAndWrite(source = source, kitName = "mydb", storageSize = "50Gi")

        val output = File(workingDir, "mydb/dashboards/clickhouse.json")
        assertThat(output).exists()
        assertThat(output).hasContent(jsonContent)
    }

    @Test
    fun `kit directory is not created when rendering fails`() {
        val source = InstallTemplateResolver.TemplateSource.Directory(File(tempDir, "templates").also { it.mkdirs() })
        whenever(mockResolver.listTemplateFiles(source))
            .thenThrow(RuntimeException("simulated render failure"))
        whenever(mockResolver.readInstallYamlContent(source)).thenReturn(null)

        assertThatThrownBy {
            Install().renderAndWrite(source = source, kitName = "mydb", storageSize = "50Gi")
        }.isInstanceOf(RuntimeException::class.java)

        assertThat(File(workingDir, "mydb")).doesNotExist()
    }
}
