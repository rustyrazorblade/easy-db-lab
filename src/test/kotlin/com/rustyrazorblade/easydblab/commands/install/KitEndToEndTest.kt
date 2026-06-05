package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitHookExecutor
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
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
        whenever(mockGrafanaDashboardService.installDashboardFromFile(any(), any(), any())).thenReturn(Result.success(Unit))
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

    @Test
    fun `collision check blocks install when kit dir already exists`() {
        val resolver = get<InstallTemplateResolver>()
        val source = resolver.resolve("testdb")
        val config =
            requireNotNull(resolver.loadInstallConfig(source))
                .copy(collisionCheck = true)

        File(workingDir, "testdb").mkdirs()
        File(workingDir, "testdb/existing.txt").writeText("occupied")

        val installCmd = KitInstallCommand(config, source)
        installCmd.argValues["STORAGE_SIZE"] = "100Gi"
        installCmd.call()

        assertThat(File(workingDir, "testdb/bin/start.sh")).doesNotExist()
    }
}
