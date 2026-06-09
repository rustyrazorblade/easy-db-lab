package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.BaseKoinTest
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class KitInstallCommandFactoryTest : BaseKoinTest() {
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
                    ServerType.Control to
                        listOf(
                            ClusterHost("54.1.2.3", "10.0.0.1", "control0", "us-west-2a", "i-ctrl"),
                        ),
                    ServerType.Cassandra to
                        listOf(
                            ClusterHost("54.2.0.0", "10.0.2.0", "db0", "us-west-2a", "i-db0"),
                            ClusterHost("54.2.0.1", "10.0.2.1", "db1", "us-west-2b", "i-db1"),
                            ClusterHost("54.2.0.2", "10.0.2.2", "db2", "us-west-2c", "i-db2"),
                        ),
                    ServerType.Stress to
                        listOf(
                            ClusterHost("54.3.0.0", "10.0.3.0", "app0", "us-west-2a", "i-app0"),
                            ClusterHost("54.3.0.1", "10.0.3.1", "app1", "us-west-2b", "i-app1"),
                        ),
                ),
        )

    private lateinit var templateVars: Map<String, String>
    private lateinit var factory: KitInstallCommandFactory
    private lateinit var directorySource: InstallTemplateResolver.TemplateSource.Directory

    @BeforeEach
    fun setup() {
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)
        templateVars = TemplateVariables.from(state = clusterState, kitName = "", storageSize = "").toMap()
        factory = KitInstallCommandFactory(templateVars)
        directorySource = InstallTemplateResolver.TemplateSource.Directory(templateDir)
    }

    private fun config(vararg args: KitArgSpec) =
        KitConfig(
            name = "mydb",
            description = "My kit",
            collisionCheck = false,
            args = args.toList(),
        )

    private fun arg(
        flag: String,
        variable: String,
        type: KitArgSpec.ArgType = KitArgSpec.ArgType.STRING,
        default: String = "",
        required: Boolean = false,
    ) = KitArgSpec(flag = flag, variable = variable, type = type, default = default, required = required)

    @Test
    fun `generated CommandLine has correct subcommand name`() {
        val cl = factory.build(config(), directorySource)
        assertThat(cl.commandName).isEqualTo("mydb")
    }

    @Test
    fun `generated CommandLine registers declared options`() {
        val cl = factory.build(config(arg("--size", "STORAGE_SIZE")), directorySource)
        val optionNames = cl.commandSpec.options().map { it.longestName() }
        assertThat(optionNames).contains("--size")
    }

    @Test
    fun `force option is added when collisionCheck is true`() {
        val cfg = KitConfig(name = "mydb", collisionCheck = true)
        val cl = factory.build(cfg, directorySource)
        val optionNames = cl.commandSpec.options().map { it.longestName() }
        assertThat(optionNames).contains("--force")
    }

    @Test
    fun `force option is absent when collisionCheck is false`() {
        val cl = factory.build(config(), directorySource)
        val optionNames = cl.commandSpec.options().map { it.longestName() }
        assertThat(optionNames).doesNotContain("--force")
    }

    @Test
    fun `cluster state variable is interpolated into default`() {
        val cl = factory.build(config(arg("--replicas", "REPLICAS", default = "\${DB_NODE_COUNT}")), directorySource)
        val opt = cl.commandSpec.findOption("--replicas")
        assertThat(opt.defaultValue()).isEqualTo("3")
    }

    @Test
    fun `unknown variable is kept as raw form in default`() {
        val factoryNoState = KitInstallCommandFactory(emptyMap())
        val cl = factoryNoState.build(config(arg("--replicas", "REPLICAS", default = "\${DB_NODE_COUNT}")), directorySource)
        val command = cl.commandSpec.userObject() as KitInstallCommand
        assertThat(command.resolvedDefaults["REPLICAS"]).isEqualTo("\${DB_NODE_COUNT}")
    }

    @Test
    fun `app node count is interpolated into default`() {
        val cl = factory.build(config(arg("--workers", "WORKERS", default = "\${APP_NODE_COUNT}")), directorySource)
        val opt = cl.commandSpec.findOption("--workers")
        assertThat(opt.defaultValue()).isEqualTo("2")
    }

    @Test
    fun `force flag sets command force to true when passed`() {
        val cfg = KitConfig(name = "mydb", collisionCheck = true)
        val cl = factory.build(cfg, directorySource)
        val command = cl.commandSpec.userObject() as KitInstallCommand
        cl.parseArgs("--force")
        assertThat(command.force).isTrue()
    }

    @Test
    fun `force flag leaves command force false when not passed`() {
        val cfg = KitConfig(name = "mydb", collisionCheck = true)
        val cl = factory.build(cfg, directorySource)
        val command = cl.commandSpec.userObject() as KitInstallCommand
        cl.parseArgs()
        assertThat(command.force).isFalse()
    }

    @Test
    fun `resolved defaults are stored on command for use in execute`() {
        val cl =
            factory.build(
                config(arg("--replicas", "REPLICAS", default = "\${DB_NODE_COUNT}")),
                directorySource,
            )
        val command = cl.commandSpec.userObject() as KitInstallCommand
        assertThat(command.resolvedDefaults["REPLICAS"]).isEqualTo("3")
    }

    @Test
    fun `kit with --version arg does not conflict with picocli mixin version option`() {
        // Kits like tidb declare --version to set the database version.
        // mixinStandardHelpOptions also registers --version, so we must not add the mixin
        // when the kit already owns that flag.
        val cl = factory.build(config(arg("--version", "TIDB_VERSION", default = "v8.5.2")), directorySource)
        val optionNames = cl.commandSpec.options().map { it.longestName() }
        assertThat(optionNames).contains("--version")
        // --help must still be present
        assertThat(optionNames).contains("--help")
    }

    @Test
    fun `kit with --version arg parses the version value correctly`() {
        val cl = factory.build(config(arg("--version", "TIDB_VERSION", default = "v8.5.2")), directorySource)
        val command = cl.commandSpec.userObject() as KitInstallCommand
        cl.parseArgs("--version", "v7.5.0")
        assertThat(command.argValues["TIDB_VERSION"]).isEqualTo("v7.5.0")
    }
}
