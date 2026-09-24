package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.StepExecutionContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

class WorkloadStepExecutorTest : BaseKoinTest() {
    private lateinit var k8sService: K8sService
    private lateinit var helmService: HelmService
    private lateinit var kubectlService: KubectlService
    private lateinit var executor: WorkloadStepExecutor

    private val controlHost =
        ClusterHost(
            publicIp = "1.2.3.4",
            privateIp = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-ctrl",
        )

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
            versions = mutableMapOf(),
            hosts = mapOf(ServerType.Cassandra to listOf(dbHost)),
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
        executor =
            WorkloadStepExecutor(
                k8sService = k8sService,
                helmService = helmService,
                kubectlService = kubectlService,
                remoteOps = get(),
                eventBus = get<EventBus>(),
            )
    }

    private fun execute(
        steps: List<InstallStep>,
        variables: Map<String, String> = emptyMap(),
        kitDir: File = tempDir,
    ): Result<Unit> =
        executor.execute(
            steps = steps,
            phase = "start",
            context =
                StepExecutionContext(
                    kitName = "testdb",
                    controlHost = controlHost,
                    clusterState = clusterState,
                    variables = variables,
                    kitDir = kitDir,
                ),
        )

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
            whenever(kubectlService.listInAllNamespaces(any(), any())).thenReturn(listOf("cluster.postgresql.cnpg.io/postgres-duckdb"))
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

            verify(helmService, never()).uninstall(any(), any(), any())
            verify(kubectlService).listInAllNamespaces(any(), org.mockito.kotlin.eq("clusters.postgresql.cnpg.io"))
            val kept = events.filterIsInstance<Event.Kit.HelmReleaseKept>().single()
            assertThat(kept.release).isEqualTo("cnpg-operator")
            assertThat(kept.usedBy).containsExactly("cluster.postgresql.cnpg.io/postgres-duckdb")
            assertThat(kept.toDisplayString()).contains("cnpg-operator", "cluster.postgresql.cnpg.io/postgres-duckdb")
        }

        @Test
        fun `uninstalls the release when no object of the type is left`() {
            whenever(kubectlService.listInAllNamespaces(any(), any())).thenReturn(emptyList())

            assertThat(execute(listOf(operatorUninstall)).isSuccess).isTrue()

            verify(helmService).uninstall(any(), org.mockito.kotlin.eq("cnpg-operator"), org.mockito.kotlin.eq("cnpg-system"))
        }

        @Test
        fun `a release with no keep-while-any is uninstalled without looking in the cluster`() {
            execute(listOf(InstallStep.HelmUninstall(release = "strimzi-operator", namespace = "strimzi")))

            verify(kubectlService, never()).listInAllNamespaces(any(), any())
            verify(helmService).uninstall(any(), org.mockito.kotlin.eq("strimzi-operator"), org.mockito.kotlin.eq("strimzi"))
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
            whenever(k8sService.createLocalPersistentVolumes(any(), any()))
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
            verify(k8sService, never()).createLocalPersistentVolumes(any(), any())
        }

        @Test
        fun `if-set runs the step when the named variable is set`() {
            whenever(k8sService.createLocalPersistentVolumes(any(), any())).thenReturn(Result.success(Unit))

            val result =
                execute(
                    steps = listOf(InstallStep.PlatformPvs(nodeType = "db", ifSet = "EXTSTORE_SIZE")),
                    variables = mapOf("EXTSTORE_SIZE" to "100G", "STORAGE_SIZE" to "100Gi"),
                )

            assertThat(result.isSuccess).isTrue()
            verify(k8sService).createLocalPersistentVolumes(any(), any())
        }

        @Test
        fun `storage-size sets the PV capacity instead of the STORAGE_SIZE variable`() {
            whenever(k8sService.createLocalPersistentVolumes(any(), any())).thenReturn(Result.success(Unit))

            val result =
                execute(
                    steps = listOf(InstallStep.PlatformPvs(nodeType = "db", storageSize = "10Ti")),
                    variables = mapOf("STORAGE_SIZE" to ""),
                )

            assertThat(result.isSuccess).isTrue()
            val config = argumentCaptor<PersistentVolumeConfig>()
            verify(k8sService).createLocalPersistentVolumes(any(), config.capture())
            assertThat(config.firstValue.storageSize).isEqualTo("10Ti")
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
            verify(k8sService, never()).createLocalPersistentVolumes(any(), any())
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
                host = controlHost.toHost(),
                kinds = listOf("deployment", "pod"),
                selector = "easydblab/kit=memcached",
                namespace = "cache",
            )
            verify(kubectlService, never()).delete(any(), any(), any(), any(), any())
        }

        @Test
        fun `a delete step by name deletes that one object`() {
            execute(listOf(InstallStep.Delete(kind = "Service", name = "\${KIT_NAME}-nodeport")), mapOf("KIT_NAME" to "pg"))

            verify(kubectlService).delete(
                host = controlHost.toHost(),
                kind = "Service",
                name = "pg-nodeport",
                namespace = "default",
                ignoreNotFound = true,
            )
            verify(kubectlService, never()).deleteBySelector(any(), any(), any(), any())
        }

        @Test
        fun `a failed selector delete fails the step`() {
            whenever(kubectlService.deleteBySelector(any(), any(), any(), any())).thenThrow(IllegalStateException("api down"))

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
                    capturedContent = inv.getArgument(1)
                }.whenever(kubectlService)
                .applyContent(any(), any())

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
                    capturedContent = inv.getArgument(1)
                }.whenever(kubectlService)
                .applyContent(any(), any())

            execute(
                steps = listOf(InstallStep.Manifest(manifestFile.name, interpolate = false)),
                variables = mapOf("IMAGE" to "ghcr.io/example/pg:17"),
                kitDir = tempDir,
            )

            assertThat(capturedContent).isEqualTo("imageName: \${IMAGE}")
        }
    }
}
