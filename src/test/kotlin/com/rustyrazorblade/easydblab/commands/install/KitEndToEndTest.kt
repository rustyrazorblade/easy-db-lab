package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.CollisionCheck
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitHookExecutor
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import com.rustyrazorblade.easydblab.services.KitType
import com.rustyrazorblade.easydblab.services.MetricsRegistryService
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.WorkloadStepExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * End-to-end integration test for the install → run kit pipeline.
 *
 * Uses the `testdb` kit from test resources — a minimal shell-based kit
 * whose scripts echo their parameters to output files. No K8s, no AWS, no network.
 *
 * Verifies:
 * - Template rendering substitutes install-time variables (__VAR__)
 * - KitRunnerCommand injects runtime env vars into the script environment
 * - Positional args (e.g. backup name) reach the script as BACKUP_NAME
 */
class KitEndToEndTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val mockGrafanaDashboardService: GrafanaDashboardService = mock()
    private val mockWorkloadStepExecutor: WorkloadStepExecutor = mock()
    private val mockMetricsRegistryService: MetricsRegistryService = mock()
    private val mockKitHookExecutor: KitHookExecutor = mock()

    private lateinit var workingDir: File

    private val controlHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-ctrl",
        )

    private val clusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = "account-bucket",
            initConfig = InitConfig(region = "us-east-1", name = "test-cluster"),
            hosts = mapOf(ServerType.Control to listOf(controlHost)),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single<GrafanaDashboardService> { mockGrafanaDashboardService }
                single<WorkloadStepExecutor> { mockWorkloadStepExecutor }
                single<MetricsRegistryService> { mockMetricsRegistryService }
                single<KitHookExecutor> { mockKitHookExecutor }
                single { TemplateService(get(), get()) }
                single { KitSourcesProvider(get()) }
                single { InstallTemplateResolver(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)
        whenever(mockGrafanaDashboardService.installDashboard(any(), any(), any())).thenReturn(Result.success(Unit))
        workingDir = get<Context>().workingDirectory
    }

    /**
     * Installs the testdb kit into the working dir using the real template renderer,
     * then runs the given phase via KitRunnerCommand.
     */
    private fun installAndRun(
        phase: String,
        argValues: Map<String, String> = emptyMap(),
        name: String = "",
    ): Int {
        val resolver = get<InstallTemplateResolver>()
        val source = resolver.resolve("testdb")
        val config = requireNotNull(resolver.loadInstallConfig(source)) { "testdb kit.yaml not found" }

        val installCmd = KitInstallCommand(config, source)
        argValues.forEach { (k, v) -> installCmd.argValues[k] = v }
        installCmd.call()

        val kitDir = File(workingDir, "testdb")
        val runCmd = KitRunnerCommand("testdb", kitDir, phase)
        if (name.isNotEmpty()) runCmd.name = name
        return runCmd.call()
    }

    @Test
    fun `start script receives install-time and runtime variables`() {
        val exitCode = installAndRun(phase = "start", argValues = mapOf("STORAGE_SIZE" to "200Gi", "REPLICAS" to "3"))

        assertThat(exitCode).isEqualTo(0)

        val params = File(workingDir, "testdb-params.txt").readText()
        assertThat(params).contains("CLUSTER_NAME=test-cluster")
        assertThat(params).contains("STORAGE_SIZE=200Gi")
        assertThat(params).contains("REPLICAS=3")
        assertThat(params).contains("REGION=us-east-1")
        assertThat(params).contains("ACCOUNT_BUCKET=account-bucket")
        assertThat(params).contains("KIT_NAME=testdb")
    }

    @Test
    fun `backup script receives BACKUP_NAME from --name option`() {
        installAndRun(phase = "start", argValues = mapOf("STORAGE_SIZE" to "100Gi"))

        val exitCode = installAndRun(phase = "backup", name = "my-snapshot-2024")

        assertThat(exitCode).isEqualTo(0)

        val params = File(workingDir, "testdb-backup-params.txt").readText()
        assertThat(params).contains("BACKUP_NAME=my-snapshot-2024")
        assertThat(params).contains("CLUSTER_NAME=test-cluster")
        assertThat(params).contains("ACCOUNT_BUCKET=account-bucket")
    }

    @Test
    fun `resolved defaults are applied when argValues not explicitly set`() {
        val resolver = get<InstallTemplateResolver>()
        val source = resolver.resolve("testdb")
        val config = requireNotNull(resolver.loadInstallConfig(source))

        val installCmd = KitInstallCommand(config, source)
        installCmd.argValues["STORAGE_SIZE"] = "50Gi"
        installCmd.resolvedDefaults["REPLICAS"] = "5"

        installCmd.call()

        val script = File(workingDir, "testdb/bin/start.sh").readText()
        assertThat(script).contains("REPLICAS=5")
    }

    private fun collisionCheckedTestdb(): Pair<KitConfig, InstallTemplateResolver.TemplateSource> {
        val resolver = get<InstallTemplateResolver>()
        val source = resolver.resolve("testdb")
        val config = requireNotNull(resolver.loadInstallConfig(source)).copy(collisionCheck = CollisionCheck.ENABLED)
        return config to source
    }

    private fun install(
        config: KitConfig,
        source: InstallTemplateResolver.TemplateSource,
        force: Boolean = false,
    ): Int {
        val installCmd = KitInstallCommand(config, source)
        installCmd.argValues["STORAGE_SIZE"] = "100Gi"
        installCmd.force = force
        return installCmd.call()
    }

    private fun captureEvents(): List<Event> {
        val captured = mutableListOf<Event>()
        get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    captured.add(envelope.event)
                }

                override fun close() = Unit
            },
        )
        return captured
    }

    @Test
    fun `second install of a collision-checked kit fails with CollisionDetected and leaves the scaffold alone`() {
        val (config, source) = collisionCheckedTestdb()
        assertThat(install(config, source)).isEqualTo(0)
        val marker = File(workingDir, "testdb/edited-by-user.txt").apply { writeText("keep me") }
        val events = captureEvents()

        val exitCode = install(config, source)

        assertThat(exitCode).isNotEqualTo(0)
        val collision = events.filterIsInstance<Event.Install.CollisionDetected>().single()
        assertThat(collision.kit).isEqualTo("testdb")
        assertThat(collision.isError()).isTrue()
        assertThat(collision.toDisplayString()).startsWith("Error:").contains("testdb", "--force")
        assertThat(events).noneMatch { it is Event.Install.ScaffoldComplete }
        assertThat(marker).hasContent("keep me")
    }

    @Test
    fun `install of a kit whose node pool is missing fails with an error-worded RequirementNotMet`() {
        val resolver = get<InstallTemplateResolver>()
        val source = resolver.resolve("testdb")
        val config = requireNotNull(resolver.loadInstallConfig(source)).copy(type = KitType.DB)
        val events = captureEvents()

        val exitCode = install(config, source)

        assertThat(exitCode).isNotEqualTo(0)
        val requirement = events.filterIsInstance<Event.Kit.RequirementNotMet>().single()
        assertThat(requirement.isError()).isTrue()
        assertThat(requirement.toDisplayString()).startsWith("Error:").contains("testdb", "db")
        assertThat(File(workingDir, "testdb")).doesNotExist()
    }

    @Test
    fun `a kit that collision-checks only start installs over its existing scaffold`() {
        val (enabled, source) = collisionCheckedTestdb()
        val config = enabled.copy(collisionCheck = CollisionCheck(setOf(Constants.Kit.PHASE_START)))
        assertThat(install(config, source)).isEqualTo(0)
        val events = captureEvents()

        assertThat(install(config, source)).isEqualTo(0)
        assertThat(events).noneMatch { it is Event.Install.CollisionDetected }
    }

    @Test
    fun `--force reinstalls a collision-checked kit over its existing scaffold`() {
        val (config, source) = collisionCheckedTestdb()
        assertThat(install(config, source)).isEqualTo(0)
        val marker = File(workingDir, "testdb/edited-by-user.txt").apply { writeText("replaced") }
        val events = captureEvents()

        val exitCode = install(config, source, force = true)

        assertThat(exitCode).isEqualTo(0)
        assertThat(events).noneMatch { it is Event.Install.CollisionDetected }
        assertThat(File(workingDir, "testdb/bin/start.sh")).exists()
        assertThat(marker).doesNotExist()
    }
}
