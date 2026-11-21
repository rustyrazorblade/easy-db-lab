package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.NodeSelector
import com.rustyrazorblade.easydblab.configuration.RoleSelectors
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.KitArgSpec
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitType
import com.rustyrazorblade.easydblab.services.RoleNodes
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.WorkloadStepExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Verifies that [KitInstallCommand] enforces node-type requirements declared in [KitConfig.type]
 * before writing any scaffold files. A role's pool is the Kubernetes nodes matching the role's
 * selector ([RoleNodes]), never the hosts `state.json` records.
 */
class KitRequirementTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val mockResolver: InstallTemplateResolver = mock()
    private val mockWorkloadStepExecutor: WorkloadStepExecutor = mock()
    private val mockK8sService: K8sService = mock()

    @TempDir
    lateinit var templateDir: File

    private val controlHost = ClusterHost("54.1.2.3", "10.0.0.1", "control0", "us-west-2a", "i-ctrl")

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
                single { RoleNodes(mockK8sService) }
            },
        )

    @BeforeEach
    fun setup() {
        workingDir = get<Context>().workingDirectory
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
        whenever(mockK8sService.nodesMatching(any())).thenReturn(Result.success(emptyList()))
    }

    /** A cluster whose only recorded host is the control node, so any pool comes from Kubernetes. */
    private fun state(roleSelectors: RoleSelectors? = null): ClusterState =
        ClusterState(
            name = "test-cluster",
            s3Bucket = "test-bucket",
            initConfig = InitConfig(region = "us-west-2", name = "test-cluster"),
            hosts = mapOf(ServerType.Control to listOf(controlHost)),
            roleSelectors = roleSelectors,
        )

    private fun nodesFor(
        labels: Map<String, String>,
        vararg names: String,
    ) {
        whenever(mockK8sService.nodesMatching(eq(labels))).thenReturn(Result.success(names.toList()))
    }

    private fun runInstall(
        kitType: KitType?,
        state: ClusterState = state(),
        args: List<KitArgSpec> = emptyList(),
        requires: List<NodeCapability> = emptyList(),
    ): KitInstallCommand {
        whenever(mockClusterStateManager.load()).thenReturn(state)
        val config = KitConfig(name = "mykit", type = kitType, args = args, requires = requires)
        val source = InstallTemplateResolver.TemplateSource.Directory(templateDir)
        return KitInstallCommand(config, source).also { it.execute() }
    }

    @Test
    fun `install fails with RequirementNotMet when type is db and no node matches the db selector`() {
        nodesFor(mapOf("type" to "app"), "n1")

        runInstall(KitType.DB)

        val failures = capturedEvents.filterIsInstance<Event.Kit.RequirementNotMet>()
        assertThat(failures).hasSize(1)
        assertThat(failures[0].kit).isEqualTo("mykit")
        assertThat(failures[0].nodeType).isEqualTo("db")
    }

    @Test
    fun `RequirementNotMet names the selector that matched no node, so a mistyped mapping shows`() {
        val mapped = RoleSelectors.OWNED_NODES.copy(db = NodeSelector(mapOf("pool" to "databsaes")))

        runInstall(KitType.DB, state(mapped))

        val failure = capturedEvents.filterIsInstance<Event.Kit.RequirementNotMet>().single()
        assertThat(failure.selector).isEqualTo("pool=databsaes")
        assertThat(failure.toDisplayString()).contains("pool=databsaes", RoleSelectors.OPTION)
    }

    @Test
    fun `install fails with RequirementNotMet when type is app and no node matches the app selector`() {
        nodesFor(mapOf("type" to "db"), "n1")

        runInstall(KitType.APP)

        val failures = capturedEvents.filterIsInstance<Event.Kit.RequirementNotMet>()
        assertThat(failures).hasSize(1)
        assertThat(failures[0].nodeType).isEqualTo("app")
    }

    @Test
    fun `install proceeds when a Kubernetes node matches the db selector though state records no db host`() {
        nodesFor(mapOf("type" to "db"), "n1")

        runInstall(KitType.DB)

        assertThat(capturedEvents.filterIsInstance<Event.Kit.RequirementNotMet>()).isEmpty()
    }

    @Test
    fun `install proceeds when type is app and a node matches the app selector`() {
        nodesFor(mapOf("type" to "app"), "n1")

        runInstall(KitType.APP)

        assertThat(capturedEvents.filterIsInstance<Event.Kit.RequirementNotMet>()).isEmpty()
    }

    @Test
    fun `an unmapped role on a cluster the user brings is satisfied by any node`() {
        nodesFor(emptyMap(), "only")

        runInstall(KitType.DB, state(RoleSelectors.ANYWHERE))

        assertThat(capturedEvents.filterIsInstance<Event.Kit.RequirementNotMet>()).isEmpty()
    }

    @Test
    fun `a node-count default resolves against the role's pool in Kubernetes`() {
        nodesFor(mapOf("type" to "db"), "n1", "n2", "n3")
        val nodes = KitArgSpec(flag = "--nodes", variable = "NODES", type = KitArgSpec.ArgType.INT, default = "\${DB_NODE_COUNT}")

        val command = runInstall(KitType.DB, args = listOf(nodes))

        assertThat(command.argValues["NODES"]).isEqualTo("3")
    }

    @Test
    fun `install proceeds when no type declared regardless of node topology`() {
        runInstall(null)

        assertThat(capturedEvents.filterIsInstance<Event.Kit.RequirementNotMet>()).isEmpty()
    }

    @Test
    fun `kit directory is not created when requirement is not met`() {
        runInstall(KitType.DB)

        assertThat(File(workingDir, "mykit")).doesNotExist()
    }

    @Test
    fun `install fails with CapabilityMissing when the kit requires host-path and the cluster lacks it`() {
        nodesFor(mapOf("type" to "db"), "n1")
        val noHostPath = state().apply { capabilities = setOf(NodeCapability.PRIVILEGED) }

        runInstall(KitType.DB, noHostPath, requires = listOf(NodeCapability.HOST_PATH))

        val failure = capturedEvents.filterIsInstance<Event.Kit.CapabilityMissing>().single()
        assertThat(
            failure,
        ).isEqualTo(Event.Kit.CapabilityMissing(kit = "mykit", phase = "install", missing = listOf(NodeCapability.HOST_PATH)))
        assertThat(failure.toDisplayString()).startsWith("Error:").contains("host-path", NodeCapability.HOST_PATH.absentReason)
        assertThat(File(workingDir, "mykit")).doesNotExist()
    }

    @Test
    fun `install proceeds when the cluster records the capability the kit requires`() {
        nodesFor(mapOf("type" to "db"), "n1")
        val withHostPath = state().apply { capabilities = setOf(NodeCapability.HOST_PATH) }

        runInstall(KitType.DB, withHostPath, requires = listOf(NodeCapability.HOST_PATH))

        assertThat(capturedEvents.filterIsInstance<Event.Kit.CapabilityMissing>()).isEmpty()
        assertThat(capturedEvents.filterIsInstance<Event.Kit.RequirementNotMet>()).isEmpty()
    }
}
