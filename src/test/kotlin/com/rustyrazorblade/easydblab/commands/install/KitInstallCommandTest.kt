package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitArgSpec
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.TemplateVariables
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Tests that KitInstallCommand creates the right directory name based on kit-ref args.
 */
class KitInstallCommandTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val mockResolver: InstallTemplateResolver = mock()

    @TempDir
    lateinit var templateDir: File

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single { TemplateService(get(), get()) }
                single<InstallTemplateResolver> { mockResolver }
            },
        )

    private val clusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = "test-bucket",
            initConfig = InitConfig(region = "us-west-2", name = "test-cluster"),
            hosts =
                mapOf(
                    ServerType.Control to listOf(ClusterHost("1.2.3.4", "10.0.0.1", "control0", "us-west-2a", "i-ctrl")),
                    ServerType.Stress to listOf(ClusterHost("2.3.4.5", "10.0.1.1", "app0", "us-west-2a", "i-app0")),
                    ServerType.Cassandra to listOf(ClusterHost("3.4.5.6", "10.0.2.1", "db0", "us-west-2a", "i-db0")),
                ),
        )

    private val sysbenchConfig =
        KitConfig(
            name = "sysbench",
            type = null,
            args =
                listOf(
                    KitArgSpec(
                        flag = "--target",
                        variable = "TARGET",
                        type = KitArgSpec.ArgType.KIT_REF,
                    ),
                ),
        )

    private lateinit var factory: KitInstallCommandFactory
    private lateinit var source: InstallTemplateResolver.TemplateSource.Directory
    private lateinit var workingDir: File

    @BeforeEach
    fun setup() {
        workingDir = get<Context>().workingDirectory
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)
        whenever(mockResolver.listTemplateFiles(org.mockito.kotlin.any())).thenReturn(emptyList())
        whenever(mockResolver.readInstallYamlContent(org.mockito.kotlin.any())).thenReturn(null)
        val templateVars = TemplateVariables.from(state = clusterState, kitName = "", storageSize = "").toMap()
        factory = KitInstallCommandFactory(templateVars)
        source = InstallTemplateResolver.TemplateSource.Directory(templateDir)
    }

    private fun buildAndRun(
        config: KitConfig,
        argValues: Map<String, String>,
    ) {
        val cl = factory.build(config, source)
        val cmd = cl.commandSpec.userObject() as KitInstallCommand
        argValues.forEach { (k, v) -> cmd.argValues[k] = v }
        cmd.execute()
    }

    @Test
    fun `kit without kit-ref arg creates directory named after kit`() {
        val config = KitConfig(name = "mydb", type = null)
        buildAndRun(config, emptyMap())
        assertThat(File(workingDir, "mydb")).isDirectory()
    }

    @Test
    fun `kit with kit-ref arg creates directory named kit-target`() {
        buildAndRun(sysbenchConfig, mapOf("TARGET" to "clickhouse"))
        assertThat(File(workingDir, "sysbench-clickhouse")).isDirectory()
        assertThat(File(workingDir, "sysbench")).doesNotExist()
    }

    @Test
    fun `two installs with different targets create separate directories`() {
        buildAndRun(sysbenchConfig, mapOf("TARGET" to "clickhouse"))
        buildAndRun(sysbenchConfig, mapOf("TARGET" to "mysql"))
        assertThat(File(workingDir, "sysbench-clickhouse")).isDirectory()
        assertThat(File(workingDir, "sysbench-mysql")).isDirectory()
    }

    @Test
    fun `kit-ref with capability passes when target has that capability`() {
        val benchConfig =
            KitConfig(
                name = "sysbench",
                type = null,
                args =
                    listOf(
                        KitArgSpec(flag = "--target", variable = "TARGET", type = KitArgSpec.ArgType.KIT_REF, capability = "sql"),
                    ),
            )
        // Write a target kit with the sql capability
        val targetDir = File(workingDir, "tidb").also { it.mkdirs() }
        File(targetDir, "kit.yaml").writeText(
            """
            name: tidb
            capabilities:
              - type: sql
                user: root
            """.trimIndent(),
        )

        buildAndRun(benchConfig, mapOf("TARGET" to "tidb"))
        assertThat(File(workingDir, "sysbench-tidb")).isDirectory()
    }

    @Test
    fun `kit-ref with capability fails when target lacks that capability`() {
        val benchConfig =
            KitConfig(
                name = "sysbench",
                type = null,
                args =
                    listOf(
                        KitArgSpec(flag = "--target", variable = "TARGET", type = KitArgSpec.ArgType.KIT_REF, capability = "sql"),
                    ),
            )
        val targetDir = File(workingDir, "presto").also { it.mkdirs() }
        File(targetDir, "kit.yaml").writeText("name: presto\n")

        assertThatThrownBy { buildAndRun(benchConfig, mapOf("TARGET" to "presto")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("sql")
            .hasMessageContaining("presto")
    }

    @Test
    fun `kit-ref fails when target kit is not installed`() {
        val benchConfig =
            KitConfig(
                name = "sysbench",
                type = null,
                args =
                    listOf(
                        KitArgSpec(flag = "--target", variable = "TARGET", type = KitArgSpec.ArgType.KIT_REF, capability = "sql"),
                    ),
            )

        assertThatThrownBy { buildAndRun(benchConfig, mapOf("TARGET" to "doesnotexist")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("doesnotexist")
            .hasMessageContaining("not installed")
    }
}
