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
import com.rustyrazorblade.easydblab.kubernetes.KubernetesService
import com.rustyrazorblade.easydblab.services.DefaultKitEndpointResolver
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import com.rustyrazorblade.easydblab.services.KitEndpointResolver
import com.rustyrazorblade.easydblab.services.KitHookExecutor
import com.rustyrazorblade.easydblab.services.KitWorkloadProbe
import com.rustyrazorblade.easydblab.services.MetricsRegistryService
import com.rustyrazorblade.easydblab.services.WorkloadStepExecutor
import org.junit.jupiter.api.BeforeEach
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration

/**
 * Shared fixture for the [KitRunnerCommand] tests: the mocked collaborators, a cluster with one
 * control and one db node, and helpers that write a kit's scripts, `kit.yaml` and resolved args
 * into the working directory. Each subclass covers one concern of the command.
 */
abstract class KitRunnerCommandTestBase : BaseKoinTest() {
    protected val mockClusterStateManager: ClusterStateManager = mock()
    protected val mockGrafanaDashboardService: GrafanaDashboardService = mock()
    protected val mockWorkloadStepExecutor: WorkloadStepExecutor = mock()
    protected val mockMetricsRegistryService: MetricsRegistryService = mock()
    protected val mockKitHookExecutor: KitHookExecutor = mock()
    protected val mockKubeService: KubernetesService = mock()

    protected lateinit var workingDir: File

    private val controlHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-ctrl",
        )

    private val dbHost =
        ClusterHost(
            publicIp = "3.4.5.6",
            privateIp = "10.0.2.1",
            alias = "db0",
            availabilityZone = "us-west-2a",
            instanceId = "i-db0",
        )

    protected val clusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = "test-bucket",
            initConfig = InitConfig(region = "us-west-2", name = "test-cluster"),
            hosts =
                mapOf(
                    ServerType.Control to listOf(controlHost),
                    ServerType.Cassandra to listOf(dbHost),
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single<GrafanaDashboardService> { mockGrafanaDashboardService }
                single<WorkloadStepExecutor> { mockWorkloadStepExecutor }
                single<MetricsRegistryService> { mockMetricsRegistryService }
                single<KitHookExecutor> { mockKitHookExecutor }
                single<KitEndpointResolver> { DefaultKitEndpointResolver() }
                single { KitWorkloadProbe(mockKubeService, mock(), pollInterval = Duration.ZERO, maxPolls = STOP_WAIT_POLLS) }
            },
        )

    @BeforeEach
    fun setup() {
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)
        whenever(mockGrafanaDashboardService.installDashboardFromFile(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockWorkloadStepExecutor.execute(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockMetricsRegistryService.register(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockMetricsRegistryService.deregister(any(), any())).thenReturn(Result.success(Unit))
        workingDir = get<Context>().workingDirectory
    }

    protected fun writeScript(
        kitName: String,
        scriptName: String,
        content: String,
    ): File {
        val binDir = File(workingDir, "$kitName/bin").also { it.mkdirs() }
        val script = File(binDir, "$scriptName.sh")
        script.writeText("#!/bin/sh\n$content\n")
        val perms =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        java.nio.file.Files
            .setPosixFilePermissions(script.toPath(), perms)
        return script
    }

    protected fun command(
        kitName: String,
        phaseName: String,
    ) = KitRunnerCommand(kitName, File(workingDir, kitName), phaseName)

    protected fun writeKitYaml(
        kitName: String,
        yaml: String,
    ) {
        val dir = File(workingDir, kitName).also { it.mkdirs() }
        File(dir, Constants.Kit.CONFIG_FILE).writeText(yaml)
    }

    protected fun captureEvents(block: () -> Unit): List<Event> {
        val captured = mutableListOf<Event>()
        get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    captured.add(envelope.event)
                }

                override fun close() = Unit
            },
        )
        block()
        return captured
    }

    protected fun writeResolvedArgs(
        kitName: String,
        vars: Map<String, String>,
    ) {
        val dir = File(workingDir, kitName).also { it.mkdirs() }
        File(dir, Constants.Kit.RESOLVED_ARGS_FILE).writeText(
            vars.entries.joinToString("\n") { (k, v) -> "$k=$v" } + "\n",
        )
    }

    protected companion object {
        /** How many times the injected probe polls a stopping kit before giving up. */
        const val STOP_WAIT_POLLS = 3
    }
}
