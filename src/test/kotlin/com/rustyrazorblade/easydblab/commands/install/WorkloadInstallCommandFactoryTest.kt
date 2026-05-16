package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.TemplateVariables
import com.rustyrazorblade.easydblab.services.WorkloadArgSpec
import com.rustyrazorblade.easydblab.services.WorkloadInstallConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class WorkloadInstallCommandFactoryTest : BaseKoinTest() {
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
    private lateinit var factory: WorkloadInstallCommandFactory
    private lateinit var adHocSource: InstallTemplateResolver.TemplateSource.AdHoc

    @BeforeEach
    fun setup() {
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)
        templateVars = TemplateVariables.from(state = clusterState, workloadName = "", storageSize = "").toMap()
        factory = WorkloadInstallCommandFactory(templateVars)
        adHocSource = InstallTemplateResolver.TemplateSource.AdHoc(templateDir)
    }

    private fun config(vararg args: WorkloadArgSpec) =
        WorkloadInstallConfig(
            name = "mydb",
            description = "My workload",
            collisionCheck = false,
            args = args.toList(),
        )

    private fun arg(
        flag: String,
        variable: String,
        type: WorkloadArgSpec.ArgType = WorkloadArgSpec.ArgType.STRING,
        default: String = "",
        suffix: String = "",
        required: Boolean = false,
    ) = WorkloadArgSpec(flag = flag, variable = variable, type = type, default = default, suffix = suffix, required = required)

    // ── flag generation ──────────────────────────────────────────────────────

    @Test
    fun `generated CommandLine has correct subcommand name`() {
        val cl = factory.build(config(), adHocSource)
        assertThat(cl.commandName).isEqualTo("mydb")
    }

    @Test
    fun `generated CommandLine registers declared options`() {
        val cl = factory.build(config(arg("--size", "STORAGE_SIZE")), adHocSource)
        val optionNames = cl.commandSpec.options().map { it.longestName() }
        assertThat(optionNames).contains("--size")
    }

    @Test
    fun `force option is added when collisionCheck is true`() {
        val cfg = WorkloadInstallConfig(name = "mydb", collisionCheck = true)
        val cl = factory.build(cfg, adHocSource)
        val optionNames = cl.commandSpec.options().map { it.longestName() }
        assertThat(optionNames).contains("--force")
    }

    @Test
    fun `force option is absent when collisionCheck is false`() {
        val cl = factory.build(config(), adHocSource)
        val optionNames = cl.commandSpec.options().map { it.longestName() }
        assertThat(optionNames).doesNotContain("--force")
    }

    // ── default interpolation ────────────────────────────────────────────────

    @Test
    fun `cluster state variable is interpolated into default`() {
        val cl = factory.build(config(arg("--replicas", "REPLICAS", default = "\${DB_NODE_COUNT}")), adHocSource)
        val opt = cl.commandSpec.findOption("--replicas")
        assertThat(opt.defaultValue()).isEqualTo("3")
    }

    @Test
    fun `unknown variable is kept as raw form in default`() {
        val factoryNoState = WorkloadInstallCommandFactory(emptyMap())
        val cl = factoryNoState.build(config(arg("--replicas", "REPLICAS", default = "\${DB_NODE_COUNT}")), adHocSource)
        val command = cl.commandSpec.userObject() as WorkloadInstallCommand
        assertThat(command.resolvedDefaults["REPLICAS"]).isEqualTo("\${DB_NODE_COUNT}")
    }

    @Test
    fun `app node count is interpolated into default`() {
        val cl = factory.build(config(arg("--workers", "WORKERS", default = "\${APP_NODE_COUNT}")), adHocSource)
        val opt = cl.commandSpec.findOption("--workers")
        assertThat(opt.defaultValue()).isEqualTo("2")
    }

    // ── suffix transform ─────────────────────────────────────────────────────

    @Test
    fun `suffix is appended to resolved default`() {
        val cl =
            factory.build(
                config(arg("--cache", "S3_CACHE_SIZE", default = "0", suffix = "Gi")),
                adHocSource,
            )
        val opt = cl.commandSpec.findOption("--cache")
        assertThat(opt.defaultValue()).isEqualTo("0Gi")
    }

    @Test
    fun `suffix is appended to user-provided value via ISetter`() {
        val cfg = config(arg("--cache", "S3_CACHE_SIZE", suffix = "Gi"))
        val cl = factory.build(cfg, adHocSource)
        val command = cl.commandSpec.userObject() as WorkloadInstallCommand

        cl.parseArgs("--cache", "100")
        assertThat(command.argValues["S3_CACHE_SIZE"]).isEqualTo("100Gi")
    }

    // ── resolved defaults applied in execute ─────────────────────────────────

    @Test
    fun `resolved defaults are stored on command for use in execute`() {
        val cl =
            factory.build(
                config(arg("--replicas", "REPLICAS", default = "\${DB_NODE_COUNT}")),
                adHocSource,
            )
        val command = cl.commandSpec.userObject() as WorkloadInstallCommand
        assertThat(command.resolvedDefaults["REPLICAS"]).isEqualTo("3")
    }
}
