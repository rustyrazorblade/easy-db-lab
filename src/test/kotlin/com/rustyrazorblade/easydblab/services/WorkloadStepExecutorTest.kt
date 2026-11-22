package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

class WorkloadStepExecutorTest : BaseKoinTest() {
    private lateinit var k8sService: K8sService
    private lateinit var helmService: HelmService
    private lateinit var kubectlService: KubectlService
    private lateinit var executor: WorkloadStepExecutor

    private val dbHost =
        ClusterHost(
            publicIp = "1.2.3.5",
            privateIp = "10.0.0.2",
            alias = "db0",
            availabilityZone = "us-west-2a",
            instanceId = "i-db0",
        )

    private val clusterState =
        ClusterState(
            name = "test-cluster",
            hosts = mapOf(ServerType.Cassandra to listOf(dbHost), ServerType.Stress to listOf(dbHost.copy(alias = "app0"))),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<K8sService> { mock<K8sService>().also { k8sService = it } }
                single<HelmService> { mock<HelmService>().also { helmService = it } }
                single<KubectlService> { mock<KubectlService>().also { kubectlService = it } }
                single<ClusterStateManager> { mock() }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        k8sService = get()
        helmService = get()
        kubectlService = get()
        executor = executorWith(get())
        workspace.mkdirs()
        File(workspace, Constants.K3s.LOCAL_KUBECONFIG).writeText("apiVersion: v1\nkind: Config\n")
    }

    /** The executor over [remoteOps], with the real fan-out and the real shell-step environment. */
    private fun executorWith(remoteOps: RemoteOperationsService) =
        WorkloadStepExecutor(
            k8sService = k8sService,
            helmService = helmService,
            kubectlService = kubectlService,
            remoteOps = remoteOps,
            eventBus = get<EventBus>(),
            hostOperations = HostOperationsService(get()),
            kitProcessEnvironment = KitProcessEnvironment(ToolWrapperInstaller()),
        )

    /** The cluster workspace the steps run in; it holds the kubeconfig every shell step needs. */
    private val workspace: File get() = File(tempDir, "workspace")

    private fun execute(
        steps: List<InstallStep>,
        variables: Map<String, String> = emptyMap(),
        kitDir: File = tempDir,
        phase: String = "start",
        state: ClusterState = clusterState,
        stepExecutor: WorkloadStepExecutor = executor,
    ): Result<Unit> =
        stepExecutor.execute(
            steps = steps,
            phase = phase,
            context =
                StepExecutionContext(
                    kitName = "testdb",
                    clusterState = state,
                    variables = variables,
                    kitDir = kitDir,
                    workspaceDir = workspace,
                ),
        )

    /** Every string field of a step supports `${VAR}`, the repository's name and url included. */
    @Test
    fun `a helm-repo step interpolates its name and url`() {
        val result =
            execute(
                listOf(InstallStep.HelmRepo(name = "\${KIT_NAME}-charts", url = "\${REPO_URL}/charts")),
                mapOf("KIT_NAME" to "pg", "REPO_URL" to "https://example.com"),
            )

        assertThat(result.isSuccess).isTrue()
        verify(helmService).repoAdd("pg-charts", "https://example.com/charts")
    }

    /** An unset variable stays a literal `${NAME}`, as it does in every other step field. */
    @Test
    fun `a helm-repo step passes an unset variable on as the literal placeholder`() {
        val result =
            execute(
                listOf(InstallStep.HelmRepo(name = "\${UNSET_NAME}", url = "\${REPO_URL}/\${UNSET_PATH}")),
                mapOf("REPO_URL" to "https://example.com"),
            )

        assertThat(result.isSuccess).isTrue()
        verify(helmService).repoAdd("\${UNSET_NAME}", "https://example.com/\${UNSET_PATH}")
    }

    /**
     * A tool failure inside a step reaches [Event.Kit.StepFailed] with what explains it: the
     * command, where it ran, the exit status and the tool's stderr.
     */
    @Test
    fun `a failed helm or kubectl call is reported as a step failure carrying its exit status and stderr`() {
        // doAnswer, not thenThrow: Mockito rejects a checked exception the Kotlin method does not declare.
        doAnswer {
            throw ClusterToolFailedException(ClusterTool.KUBECTL, listOf("apply", "-f", "-"), 1, "control0 (1.2.3.4)", "error: forbidden\n")
        }.whenever(kubectlService).createNamespace(any())
        val events = mutableListOf<Event>()
        get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )

        val result = execute(listOf(InstallStep.Namespace(name = "cache")))

        assertThat(result.exceptionOrNull()).isInstanceOf(ClusterToolFailedException::class.java)
        val failed = events.filterIsInstance<Event.Kit.StepFailed>().single()
        assertThat(failed.stepType).isEqualTo("Namespace")
        assertThat(failed.error)
            .contains("kubectl apply -f - failed on control0 (1.2.3.4) with exit status 1")
            .contains("stderr:\nerror: forbidden")
    }

    /** A step over every node of a type, the node that fails it, and how to tell that node ran. */
    class NodeStepCase(
        val name: String,
        val step: InstallStep,
    ) {
        override fun toString() = name
    }

    /** [count] db nodes, `db0` to `db<count-1>`, and the cluster state that holds them. */
    private fun dbNodes(count: Int): ClusterState =
        clusterState.copy(
            hosts = mapOf(ServerType.Cassandra to (0 until count).map { dbHost.copy(alias = "db$it", privateIp = "10.0.0.${10 + it}") }),
        )

    /**
     * A remote-ops fake that records each node it runs on and fails on the nodes in [failOn]. The
     * failure is thrown, not a failed Result: a Mockito answer of Result.failure reads as success.
     */
    private fun recordingRemoteOps(
        touched: MutableList<String>,
        failOn: () -> Set<String>,
        beforeAnswer: () -> Unit = {},
    ): RemoteOperationsService =
        mock<RemoteOperationsService>().also { remoteOps ->
            whenever(remoteOps.executeRemotely(any(), any(), any(), any())).thenAnswer { invocation ->
                val alias = invocation.getArgument<Host>(0).alias
                touched += alias
                beforeAnswer()
                if (alias in failOn()) error("disk is read-only") else Response("")
            }
        }

    /**
     * Each per-node step runs on every node, whatever happens on the others, and a node that
     * fails is named in the step's failure. The control run, with no failing node, shows the same
     * step succeeding on all three.
     */
    @ParameterizedTest
    @MethodSource("nodeSteps")
    fun `a per-node step runs on every node and names the node that failed`(case: NodeStepCase) {
        val state = dbNodes(3)
        val touched = Collections.synchronizedList(mutableListOf<String>())
        var failOn = emptySet<String>()
        whenever(k8sService.labelNodes(any(), any())).thenAnswer { invocation ->
            invocation.getArgument<List<String>>(0).associateWith { alias ->
                touched += alias
                if (alias in failOn) Result.failure(IllegalStateException("disk is read-only")) else Result.success(Unit)
            }
        }
        whenever(k8sService.deleteLocalPersistentVolumes(any())).thenReturn(Result.success(Unit))
        whenever(k8sService.createLocalPersistentVolumes(any())).thenReturn(Result.success(Unit))
        val fanOutExecutor = executorWith(recordingRemoteOps(touched, { failOn }))
        val run = { execute(listOf(case.step), mapOf("STORAGE_SIZE" to "1Gi"), state = state, stepExecutor = fanOutExecutor) }

        assertThat(run().isSuccess).describedAs("control: no node fails").isTrue()
        assertThat(touched).containsExactlyInAnyOrder("db0", "db1", "db2")

        touched.clear()
        failOn = setOf("db1")
        val failure = run().exceptionOrNull()

        assertThat(touched).containsExactlyInAnyOrder("db0", "db1", "db2")
        assertThat(failure).isInstanceOf(NodeStepFailedException::class.java).hasMessageContaining("1 node(s): db1: disk is read-only")
        assertThat((failure as NodeStepFailedException).failures.map { it.first }).containsExactly("db1")
    }

    /**
     * The SSH per-node steps run their nodes at the same time, not one after another: each node's
     * command waits at a barrier that opens only when all three nodes have reached it. A sequential
     * loop leaves the first node waiting alone, its barrier times out, and the step fails.
     */
    @ParameterizedTest
    @MethodSource("sshNodeSteps")
    @Timeout(30)
    fun `an SSH per-node step runs its nodes at the same time`(case: NodeStepCase) {
        val barrier = CyclicBarrier(3)
        val touched = Collections.synchronizedList(mutableListOf<String>())
        val remoteOps = recordingRemoteOps(touched, { emptySet() }) { barrier.await(5, TimeUnit.SECONDS) }
        whenever(k8sService.deleteLocalPersistentVolumes(any())).thenReturn(Result.success(Unit))
        whenever(k8sService.createLocalPersistentVolumes(any())).thenReturn(Result.success(Unit))

        val result = execute(listOf(case.step), mapOf("STORAGE_SIZE" to "1Gi"), state = dbNodes(3), stepExecutor = executorWith(remoteOps))

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(touched).containsExactlyInAnyOrder("db0", "db1", "db2")
    }

    /**
     * A step that fails on more nodes than [Constants.Scale.MAX_NAMED_IN_ERROR] names that many, with
     * their errors, and counts the rest, so the message and the event stay readable on a large
     * cluster. [NodeStepFailedException.failures] still holds every node.
     */
    @Test
    fun `a step failing on many nodes names the first ones and counts the rest`() {
        val nodes = Constants.Scale.MAX_NAMED_IN_ERROR + 5
        val touched = Collections.synchronizedList(mutableListOf<String>())
        whenever(k8sService.deleteLocalPersistentVolumes(any())).thenReturn(Result.success(Unit))
        val failAll = executorWith(recordingRemoteOps(touched, { (0 until nodes).map { "db$it" }.toSet() }))

        val failure =
            execute(
                listOf(InstallStep.PlatformPvsDelete(nodeType = "db")),
                state = dbNodes(nodes),
                stepExecutor = failAll,
            ).exceptionOrNull()

        assertThat(failure).isInstanceOf(NodeStepFailedException::class.java)
        val message = failure?.message.orEmpty()
        val last = "db${nodes - 1}"
        assertThat(message)
            .startsWith("Deleting /mnt/db1/testdb failed on $nodes node(s): db0: disk is read-only")
            .contains("db${Constants.Scale.MAX_NAMED_IN_ERROR - 1}: disk is read-only")
            .endsWith("; and 5 more")
            .doesNotContain("$last:")
        assertThat((failure as NodeStepFailedException).failures).hasSize(nodes)
    }

    @Test
    fun `a platform-pvs-delete step interpolates its node-type`() {
        val remoteOps = mock<RemoteOperationsService>()
        whenever(k8sService.deleteLocalPersistentVolumes(any())).thenReturn(Result.success(Unit))
        val recordingExecutor = executorWith(remoteOps)

        val result =
            recordingExecutor.execute(
                steps = listOf(InstallStep.PlatformPvsDelete(nodeType = "\${POOL}")),
                phase = "uninstall",
                context =
                    StepExecutionContext(
                        kitName = "testdb",
                        clusterState = clusterState,
                        variables = mapOf("POOL" to "app"),
                        kitDir = tempDir,
                        workspaceDir = workspace,
                    ),
            )

        assertThat(result.isSuccess).isTrue()
        val hosts = argumentCaptor<Host>()
        verify(remoteOps).executeRemotely(hosts.capture(), eq("sudo rm -rf /mnt/db1/testdb"), any(), any())
        assertThat(hosts.allValues.map { it.alias }).containsExactly("app0")
    }

    @Test
    fun `a label step interpolates its node-type`() {
        whenever(k8sService.labelNodes(any(), any())).thenReturn(mapOf("app0" to Result.success(Unit)))

        val result =
            execute(listOf(InstallStep.Label(nodeType = "\${POOL}", labels = mapOf("kit" to "x"))), mapOf("POOL" to "app"))

        assertThat(result.isSuccess).isTrue()
        verify(k8sService).labelNodes(listOf("app0"), mapOf("kit" to "x"))
    }

    @Test
    fun `a label step interpolates the keys of its labels`() {
        whenever(k8sService.labelNodes(any(), any())).thenReturn(mapOf("db0" to Result.success(Unit)))

        val result =
            execute(
                listOf(InstallStep.Label(labels = mapOf("\${LABEL_KEY}" to "bench"))),
                mapOf("LABEL_KEY" to "workload"),
            )

        assertThat(result.isSuccess).isTrue()
        verify(k8sService).labelNodes(listOf("db0"), mapOf("workload" to "bench"))
    }

    /**
     * One operator can serve several kit instances: plain `postgres` and every `postgres-<extension>`
     * share the CNPG operator release. `keep-while-any` keeps the release while any object of that
     * type (a CNPG Cluster) is left, so uninstalling one instance does not remove the operator the
     * others still run on.
     */
    @Nested
    inner class HelmUninstallStep {
        private val operatorUninstall =
            InstallStep.HelmUninstall(release = "cnpg-operator", namespace = "cnpg-system", keepWhileAny = "clusters.postgresql.cnpg.io")

        @Test
        fun `keeps the release, and says what still uses it, while an object of the type is left`() {
            whenever(kubectlService.listInAllNamespaces(any())).thenReturn(listOf("cluster.postgresql.cnpg.io/postgres-duckdb"))
            val events = mutableListOf<Event>()
            get<EventBus>().addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        events += envelope.event
                    }

                    override fun close() = Unit
                },
            )

            assertThat(execute(listOf(operatorUninstall)).isSuccess).isTrue()

            verify(helmService, never()).uninstall(any(), any())
            verify(kubectlService).listInAllNamespaces(org.mockito.kotlin.eq("clusters.postgresql.cnpg.io"))
            val kept = events.filterIsInstance<Event.Kit.HelmReleaseKept>().single()
            assertThat(kept.release).isEqualTo("cnpg-operator")
            assertThat(kept.usedBy).containsExactly("cluster.postgresql.cnpg.io/postgres-duckdb")
            assertThat(kept.toDisplayString()).contains("cnpg-operator", "cluster.postgresql.cnpg.io/postgres-duckdb")
        }

        @Test
        fun `uninstalls the release when no object of the type is left`() {
            whenever(kubectlService.listInAllNamespaces(any())).thenReturn(emptyList())

            assertThat(execute(listOf(operatorUninstall)).isSuccess).isTrue()

            verify(helmService).uninstall(org.mockito.kotlin.eq("cnpg-operator"), org.mockito.kotlin.eq("cnpg-system"))
        }

        @Test
        fun `a release with no keep-while-any is uninstalled without looking in the cluster`() {
            execute(listOf(InstallStep.HelmUninstall(release = "strimzi-operator", namespace = "strimzi")))

            verify(kubectlService, never()).listInAllNamespaces(any())
            verify(helmService).uninstall(org.mockito.kotlin.eq("strimzi-operator"), org.mockito.kotlin.eq("strimzi"))
        }
    }

    @Nested
    inner class ShellStep {
        @Test
        fun `succeeds when script exits 0`() {
            val result = execute(listOf(InstallStep.Shell("exit 0")))
            assertThat(result.isSuccess).isTrue()
        }

        @Test
        fun `fails with the exit code and the tail of the script's output`() {
            val script = (1..30).joinToString("\n") { "echo line-$it" } + "\necho boom >&2\nexit 3"

            val failure = execute(listOf(InstallStep.Shell(script))).exceptionOrNull()

            assertThat(failure).isInstanceOf(ShellStepFailedException::class.java)
            failure as ShellStepFailedException
            assertThat(failure.exitCode).isEqualTo(3)
            // stderr is part of the output, and only the last lines are kept.
            assertThat(failure.outputTail).endsWith("line-30", "boom")
            assertThat(failure.outputTail).hasSize(Constants.Kit.SHELL_STEP_OUTPUT_TAIL_LINES)
            assertThat(failure.outputTail).doesNotContain("line-1")
        }

        @Test
        fun `a failed shell step is reported as a shell step failure with its exit code and output`() {
            val events = mutableListOf<Event>()
            get<EventBus>().addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        events += envelope.event
                    }

                    override fun close() = Unit
                },
            )

            execute(listOf(InstallStep.Shell("echo kubectl said no\nexit 1")))

            val failed = events.filterIsInstance<Event.Kit.ShellStepFailed>().single()
            assertThat(failed.exitCode).isEqualTo(1)
            assertThat(failed.stepIndex).isZero()
            assertThat(failed.outputTail).containsExactly("kubectl said no")
            assertThat(events.filterIsInstance<Event.Kit.StepFailed>()).isEmpty()
        }

        @Test
        fun `injects variables as environment variables`() {
            val outFile = File(tempDir, "env-check.txt")
            val result =
                execute(
                    steps = listOf(InstallStep.Shell("echo \$MY_VAR > ${outFile.absolutePath}")),
                    variables = mapOf("MY_VAR" to "hello-from-test"),
                )
            assertThat(result.isSuccess).isTrue()
            assertThat(outFile.readText().trim()).isEqualTo("hello-from-test")
        }

        @ParameterizedTest
        @ValueSource(strings = [Constants.Kit.PHASE_INSTALL, Constants.Kit.PHASE_START])
        fun `runs with the absolute workspace kubeconfig and the workspace bin first on PATH`(phase: String) {
            val outFile = File(tempDir, "env-check.txt")
            val kitDir = File(workspace, "testdb").apply { mkdirs() }

            val result =
                execute(
                    steps =
                        listOf(
                            InstallStep.Shell(
                                "echo \"\$KUBECONFIG\" > ${outFile.absolutePath}\necho \"\$PATH\" >> ${outFile.absolutePath}",
                            ),
                        ),
                    variables = mapOf("KUBECONFIG" to "kubeconfig"),
                    kitDir = kitDir,
                    phase = phase,
                )

            assertThat(result.isSuccess).withFailMessage(result.toString()).isTrue()
            val (kubeconfig, path) = outFile.readLines()
            assertThat(kubeconfig).isEqualTo(File(workspace, Constants.K3s.LOCAL_KUBECONFIG).absolutePath)
            assertThat(File(kubeconfig)).isFile()
            assertThat(path.split(File.pathSeparator).first()).isEqualTo(File(workspace, Constants.ToolWrappers.DIRECTORY).absolutePath)
            assertThat(path.split(File.pathSeparator).drop(1).joinToString(File.pathSeparator)).isEqualTo(System.getenv("PATH"))
        }

        @Test
        fun `fails naming the kubeconfig and does not run the script when the workspace has none`() {
            val kubeconfig = File(workspace, Constants.K3s.LOCAL_KUBECONFIG).apply { delete() }
            val ran = File(tempDir, "ran.txt")

            val failure = execute(listOf(InstallStep.Shell("touch ${ran.absolutePath}"))).exceptionOrNull()

            assertThat(failure).hasMessageContaining(kubeconfig.absolutePath)
            assertThat(ran).doesNotExist()
        }
    }

    @Nested
    inner class PlatformPvsStep {
        @Test
        fun `fails with invalid node-type`() {
            val result =
                execute(
                    steps = listOf(InstallStep.PlatformPvs(nodeType = "invalid")),
                    variables = mapOf("STORAGE_SIZE" to "100Gi"),
                )
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).contains("Invalid node-type")
        }

        @Test
        fun `fails when STORAGE_SIZE variable is missing`() {
            val result = execute(listOf(InstallStep.PlatformPvs(nodeType = "db")))
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).contains("STORAGE_SIZE")
        }

        @Test
        fun `succeeds with valid db node-type and STORAGE_SIZE`() {
            whenever(k8sService.createLocalPersistentVolumes(any()))
                .thenReturn(Result.success(Unit))

            val result =
                execute(
                    steps = listOf(InstallStep.PlatformPvs(nodeType = "db")),
                    variables = mapOf("STORAGE_SIZE" to "100Gi"),
                )
            assertThat(result.isSuccess).isTrue()
        }

        @Test
        fun `if-set skips the step when the named variable is blank`() {
            val result =
                execute(
                    steps = listOf(InstallStep.PlatformPvs(nodeType = "db", ifSet = "EXTSTORE_SIZE")),
                    variables = mapOf("EXTSTORE_SIZE" to "", "STORAGE_SIZE" to "100Gi"),
                )

            assertThat(result.isSuccess).isTrue()
            verify(k8sService, never()).createLocalPersistentVolumes(any())
        }

        @Test
        fun `if-set runs the step when the named variable is set`() {
            whenever(k8sService.createLocalPersistentVolumes(any())).thenReturn(Result.success(Unit))

            val result =
                execute(
                    steps = listOf(InstallStep.PlatformPvs(nodeType = "db", ifSet = "EXTSTORE_SIZE")),
                    variables = mapOf("EXTSTORE_SIZE" to "100G", "STORAGE_SIZE" to "100Gi"),
                )

            assertThat(result.isSuccess).isTrue()
            verify(k8sService).createLocalPersistentVolumes(any())
        }

        @Test
        fun `storage-size sets the PV capacity instead of the STORAGE_SIZE variable`() {
            whenever(k8sService.createLocalPersistentVolumes(any())).thenReturn(Result.success(Unit))

            val result =
                execute(
                    steps = listOf(InstallStep.PlatformPvs(nodeType = "db", storageSize = "10Ti")),
                    variables = mapOf("STORAGE_SIZE" to ""),
                )

            assertThat(result.isSuccess).isTrue()
            val config = argumentCaptor<PersistentVolumeConfig>()
            verify(k8sService).createLocalPersistentVolumes(config.capture())
            assertThat(config.firstValue.storageSize).isEqualTo("10Ti")
        }

        /** db and app nodes both carry ordinals; a PV must also require the pool its step names. */
        @Test
        fun `the PVs are pinned to the node pool the step names`() {
            whenever(k8sService.createLocalPersistentVolumes(any())).thenReturn(Result.success(Unit))

            execute(steps = listOf(InstallStep.PlatformPvs(nodeType = "app")), variables = mapOf("STORAGE_SIZE" to "1Gi"))

            val config = argumentCaptor<PersistentVolumeConfig>()
            verify(k8sService).createLocalPersistentVolumes(config.capture())
            assertThat(config.firstValue.nodeType).isEqualTo("app")
        }

        @Test
        fun `node-type accepts a variable`() {
            val config = createdConfig(InstallStep.PlatformPvs(nodeType = "\${POOL}"), "POOL" to "app")

            assertThat(config.nodeType).isEqualTo("app")
        }

        @Test
        fun `storage-class accepts a variable`() {
            val config = createdConfig(InstallStep.PlatformPvs(storageClass = "\${CLASS}"), "CLASS" to "fast-local")

            assertThat(config.storageClass).isEqualTo("fast-local")
        }

        /** An unset variable stays a literal `${NAME}`, as it does in every other step field. */
        @Test
        fun `an unset variable in storage-class is passed on as the literal placeholder`() {
            val config = createdConfig(InstallStep.PlatformPvs(storageClass = "\${UNSET}"), "OTHER" to "x")

            assertThat(config.storageClass).isEqualTo("\${UNSET}")
        }

        /** A literal placeholder is no node pool, so the step fails naming it instead of finding no nodes. */
        @Test
        fun `an unset variable in node-type fails the step and names the placeholder`() {
            val result = execute(listOf(InstallStep.PlatformPvs(nodeType = "\${UNSET}")), mapOf("STORAGE_SIZE" to "1Gi"))

            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("'\${UNSET}'")
            verify(k8sService, never()).createLocalPersistentVolumes(any())
        }

        @Test
        fun `volume-claim-template-name accepts a variable`() {
            val config = createdConfig(InstallStep.PlatformPvs(volumeClaimTemplateName = "\${CLAIM}"), "CLAIM" to "scratch")

            assertThat(config.volumeClaimTemplateName).isEqualTo("scratch")
        }

        /** Runs [step] with [variable] and a storage size, and returns the PV config the step created. */
        private fun createdConfig(
            step: InstallStep.PlatformPvs,
            variable: Pair<String, String>,
        ): PersistentVolumeConfig {
            whenever(k8sService.createLocalPersistentVolumes(any())).thenReturn(Result.success(Unit))

            val result = execute(listOf(step), mapOf(variable, "STORAGE_SIZE" to "1Gi"))

            assertThat(result.isSuccess).isTrue()
            val config = argumentCaptor<PersistentVolumeConfig>()
            verify(k8sService).createLocalPersistentVolumes(config.capture())
            return config.firstValue
        }

        @Test
        fun `a blank STORAGE_SIZE fails the step instead of creating a PV with no capacity`() {
            val result =
                execute(
                    steps = listOf(InstallStep.PlatformPvs(nodeType = "db")),
                    variables = mapOf("STORAGE_SIZE" to ""),
                )

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).contains("STORAGE_SIZE")
            verify(k8sService, never()).createLocalPersistentVolumes(any())
        }
    }

    @Nested
    inner class DeleteStep {
        @Test
        fun `a delete step with a selector deletes by label, with the selector and namespace interpolated`() {
            val result =
                execute(
                    steps =
                        listOf(
                            InstallStep.Delete(
                                kinds = listOf("deployment", "pod"),
                                selector = "easydblab/kit=\${KIT_NAME}",
                                namespace = "\${NS}",
                            ),
                        ),
                    variables = mapOf("KIT_NAME" to "memcached", "NS" to "cache"),
                )

            assertThat(result.isSuccess).isTrue()
            verify(kubectlService).deleteBySelector(
                kinds = listOf("deployment", "pod"),
                selector = "easydblab/kit=memcached",
                namespace = "cache",
            )
            verify(kubectlService, never()).delete(any(), any(), any(), any())
        }

        @Test
        fun `a delete step by name deletes that one object`() {
            execute(listOf(InstallStep.Delete(kind = "Service", name = "\${KIT_NAME}-nodeport")), mapOf("KIT_NAME" to "pg"))

            verify(kubectlService).delete(
                kind = "Service",
                name = "pg-nodeport",
                namespace = "default",
                ignoreNotFound = true,
            )
            verify(kubectlService, never()).deleteBySelector(any(), any(), any())
        }

        @Test
        fun `a failed selector delete fails the step`() {
            whenever(kubectlService.deleteBySelector(any(), any(), any())).thenThrow(IllegalStateException("api down"))

            val result = execute(listOf(InstallStep.Delete(kinds = listOf("pod"), selector = "a=b")))

            assertThat(result.exceptionOrNull()).hasMessage("api down")
        }
    }

    @Nested
    inner class ManifestStep {
        @Test
        fun `fails when manifest file does not exist`() {
            val result = execute(listOf(InstallStep.Manifest("nonexistent.yaml")))
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).contains("Manifest file not found")
        }

        @Test
        fun `succeeds when manifest file exists`() {
            val manifestFile = File(tempDir, "deploy.yaml").also { it.writeText("kind: Pod") }

            val result = execute(steps = listOf(InstallStep.Manifest(manifestFile.name)), kitDir = tempDir)
            assertThat(result.isSuccess).isTrue()
        }

        @Test
        fun `interpolate=true substitutes step vars in manifest content`() {
            var capturedContent: String? = null
            val manifestFile = File(tempDir, "cluster.yaml").also { it.writeText("imageName: \${IMAGE}") }
            org.mockito.kotlin
                .doAnswer { inv ->
                    capturedContent = inv.getArgument(0)
                }.whenever(kubectlService)
                .applyContent(any())

            execute(
                steps = listOf(InstallStep.Manifest(manifestFile.name, interpolate = true)),
                variables = mapOf("IMAGE" to "ghcr.io/example/pg:17"),
                kitDir = tempDir,
            )

            assertThat(capturedContent).isEqualTo("imageName: ghcr.io/example/pg:17")
        }

        @Test
        fun `interpolate=false leaves step vars in manifest content untouched`() {
            var capturedContent: String? = null
            val manifestFile = File(tempDir, "cluster.yaml").also { it.writeText("imageName: \${IMAGE}") }
            org.mockito.kotlin
                .doAnswer { inv ->
                    capturedContent = inv.getArgument(0)
                }.whenever(kubectlService)
                .applyContent(any())

            execute(
                steps = listOf(InstallStep.Manifest(manifestFile.name, interpolate = false)),
                variables = mapOf("IMAGE" to "ghcr.io/example/pg:17"),
                kitDir = tempDir,
            )

            assertThat(capturedContent).isEqualTo("imageName: \${IMAGE}")
        }
    }

    companion object {
        @JvmStatic
        fun nodeSteps() =
            listOf(
                NodeStepCase("platform-pvs-delete rm -rf", InstallStep.PlatformPvsDelete(nodeType = "db")),
                NodeStepCase("platform-pvs mkdir -p", InstallStep.PlatformPvs(nodeType = "db")),
                NodeStepCase("label", InstallStep.Label(labels = mapOf("kit" to "x"), nodeType = "db")),
            )

        @JvmStatic
        fun sshNodeSteps() = nodeSteps().filterNot { it.step is InstallStep.Label }
    }
}
