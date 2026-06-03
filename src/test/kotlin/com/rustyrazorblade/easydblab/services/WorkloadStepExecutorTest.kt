package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.StepExecutionContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
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

    @Nested
    inner class ShellStep {
        @Test
        fun `succeeds when script exits 0`() {
            val result = execute(listOf(InstallStep.Shell("exit 0")))
            assertThat(result.isSuccess).isTrue()
        }

        @Test
        fun `fails when script exits non-zero`() {
            val result = execute(listOf(InstallStep.Shell("exit 1")))
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
            assertThat(result.exceptionOrNull()?.message).contains("Shell step exited with code 1")
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
            whenever(k8sService.applyYaml(any(), any())).thenReturn(Result.success(Unit))

            val result = execute(steps = listOf(InstallStep.Manifest(manifestFile.name)), kitDir = tempDir)
            assertThat(result.isSuccess).isTrue()
        }
    }
}
