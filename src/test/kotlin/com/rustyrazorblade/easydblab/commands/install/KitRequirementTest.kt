package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitType
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.WorkloadStepExecutor
import org.assertj.core.api.Assertions.assertThat
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
 * Verifies that [KitInstallCommand] enforces node-type requirements declared in [KitConfig.type]
 * before writing any scaffold files.
 */
class KitRequirementTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val mockResolver: InstallTemplateResolver = mock()
    private val mockWorkloadStepExecutor: WorkloadStepExecutor = mock()

    @TempDir
    lateinit var templateDir: File

    private val controlHost = ClusterHost("54.1.2.3", "10.0.0.1", "control0", "us-west-2a", "i-ctrl")
    private val dbNode = ClusterHost("54.2.0.0", "10.0.2.0", "db0", "us-west-2a", "i-db0")
    private val appNode = ClusterHost("54.3.0.0", "10.0.3.0", "app0", "us-west-2a", "i-app0")

    private val capturedEvents = mutableListOf<Event>()
    private lateinit var eventBus: EventBus
    private lateinit var workingDir: File

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single { TemplateService(get(), get()) }
                single<InstallTemplateResolver> { mockResolver }
                single<WorkloadStepExecutor> { mockWorkloadStepExecutor }
            },
        )

    @BeforeEach
    fun setup() {
        workingDir = get<com.rustyrazorblade.easydblab.Context>().workingDirectory
        capturedEvents.clear()
        eventBus = get()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    capturedEvents.add(envelope.event)
                }

                override fun close() = Unit
            },
        )
        val source = InstallTemplateResolver.TemplateSource.Directory(templateDir)
        whenever(mockResolver.listTemplateFiles(source)).thenReturn(emptyList())
        whenever(mockResolver.readInstallYamlContent(source)).thenReturn(null)
    }

    private fun stateWithHosts(vararg pairs: Pair<ServerType, List<ClusterHost>>): ClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = "test-bucket",
            initConfig = InitConfig(region = "us-west-2", name = "test-cluster"),
            hosts = mapOf(ServerType.Control to listOf(controlHost)) + pairs.toMap(),
        )

    private fun runInstall(
        kitType: KitType?,
        state: ClusterState,
    ) {
        whenever(mockClusterStateManager.load()).thenReturn(state)
        val config = KitConfig(name = "mykit", type = kitType)
        val source = InstallTemplateResolver.TemplateSource.Directory(templateDir)
        KitInstallCommand(config, source).execute()
    }

    @Test
    fun `install fails with RequirementNotMet when type is db and no db nodes exist`() {
        runInstall(KitType.DB, stateWithHosts())

        val failures = capturedEvents.filterIsInstance<Event.Kit.RequirementNotMet>()
        assertThat(failures).hasSize(1)
        assertThat(failures[0].kit).isEqualTo("mykit")
        assertThat(failures[0].nodeType).isEqualTo("db")
    }

    @Test
    fun `install fails with RequirementNotMet when type is app and no app nodes exist`() {
        runInstall(KitType.APP, stateWithHosts(ServerType.Cassandra to listOf(dbNode)))

        val failures = capturedEvents.filterIsInstance<Event.Kit.RequirementNotMet>()
        assertThat(failures).hasSize(1)
        assertThat(failures[0].nodeType).isEqualTo("app")
    }

    @Test
    fun `install proceeds when type is db and db nodes exist`() {
        runInstall(KitType.DB, stateWithHosts(ServerType.Cassandra to listOf(dbNode)))

        assertThat(capturedEvents.filterIsInstance<Event.Kit.RequirementNotMet>()).isEmpty()
    }

    @Test
    fun `install proceeds when type is app and app nodes exist`() {
        runInstall(KitType.APP, stateWithHosts(ServerType.Stress to listOf(appNode)))

        assertThat(capturedEvents.filterIsInstance<Event.Kit.RequirementNotMet>()).isEmpty()
    }

    @Test
    fun `install proceeds when no type declared regardless of node topology`() {
        runInstall(null, stateWithHosts())

        assertThat(capturedEvents.filterIsInstance<Event.Kit.RequirementNotMet>()).isEmpty()
    }

    @Test
    fun `kit directory is not created when requirement is not met`() {
        runInstall(KitType.DB, stateWithHosts())

        assertThat(File(workingDir, "mykit")).doesNotExist()
    }
}
