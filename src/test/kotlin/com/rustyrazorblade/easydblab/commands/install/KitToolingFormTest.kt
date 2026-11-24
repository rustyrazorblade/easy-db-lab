package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.PackagedExecutable
import com.rustyrazorblade.easydblab.configuration.CLUSTER_STATE
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import com.rustyrazorblade.easydblab.services.GrafanaClient
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.K8sReadOperations
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitHookExecutor
import com.rustyrazorblade.easydblab.services.MetricsRegistryService
import com.rustyrazorblade.easydblab.services.TemplateVariables
import com.rustyrazorblade.easydblab.services.servicesModule
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Where a kit's helm and kubectl run, through the production `ClusterTooling` binding of
 * [servicesModule]: over SSH on the control node in an EC2 + K3s workspace, and as local processes
 * through the workspace `bin/` wrappers in a K3s in Docker or a kubeconfig workspace, which record no
 * control node at all.
 *
 * The production [servicesModule] is loaded first, so its kit services, `ClusterTooling` binding and
 * helm service are the real ones; the core test modules and the bindings below replace only the
 * boundaries: SSH ([RemoteOperationsService], mocked, as CLAUDE.md allows for remote CLI tools), the
 * Kubernetes API, Grafana, metrics and hooks. The workspace wrappers are a stand-in script that
 * appends each call to `tool-calls.log`, so no helm or kubectl is needed.
 */
class KitToolingFormTest : BaseKoinTest() {
    private val remoteOps: RemoteOperationsService = mock()
    private val k8s: K8sService = mock()
    private val resolver: InstallTemplateResolver = mock()

    @TempDir
    lateinit var templateDir: File

    private lateinit var workspace: File
    private lateinit var states: ClusterStateManager

    private val controlHost = ClusterHost("54.1.2.3", "10.0.0.1", "control0", "us-west-2a", "i-ctrl")

    override fun coreTestModules(): List<Module> = listOf(servicesModule) + super.coreTestModules()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { ClusterStateManager(File(get<Context>().workingDirectory, CLUSTER_STATE)) }
                single<RemoteOperationsService> { remoteOps }
                single { k8s } binds arrayOf(K8sService::class, K8sReadOperations::class)
                single { ToolWrapperInstaller(wrapper = PackagedExecutable(RECORDING_WRAPPER)) }
                single<InstallTemplateResolver> { resolver }
                single<GrafanaClient> { mock() }
                single<MetricsRegistryService> { mock() }
                single<KitHookExecutor> { mock() }
            },
        )

    @BeforeEach
    fun setup() {
        workspace = context.workingDirectory
        states = get()
        whenever(remoteOps.executeRemotely(any(), any(), any(), any())).thenReturn(Response(""))
        whenever(k8s.nodesMatching(any())).thenReturn(Result.success(listOf("node0")))
        whenever(k8s.listPodsByLabel(any(), any())).thenReturn(Result.success(emptyList()))
        whenever(resolver.listTemplateFiles(any())).thenReturn(emptyList())
        whenever(resolver.readInstallYamlContent(any())).thenReturn(null)
    }

    private fun ec2State() =
        ClusterState(
            name = "test-cluster",
            initConfig = InitConfig(region = "us-west-2", name = "test-cluster"),
            hosts = mapOf(ServerType.Control to listOf(controlHost)),
        )

    /** A workspace in [environment] with no host recorded, so no control node exists to reach. */
    private fun localState(environment: Environment) =
        ClusterState(
            name = "test-cluster",
            environment = environment,
            initConfig = InitConfig(region = "", name = "test-cluster"),
            clientNetworks = listOf("172.18.0.0/16"),
        )

    private fun writeKubeconfig() = File(workspace, Constants.K3s.LOCAL_KUBECONFIG).writeText("apiVersion: v1\nkind: Config\n")

    /** A collision-checked kit with a helm runtime, so `start` asks helm first, then runs a helm step. */
    private fun writeHelmKit() {
        val dir = File(workspace, "mydb").also { it.mkdirs() }
        File(dir, Constants.Kit.CONFIG_FILE).writeText(
            """
            name: mydb
            collision-check: true
            runtime:
              type: helm
              release: mydb
            start:
              - type: helm-repo
                name: myrepo
                url: https://charts.example.com
            """.trimIndent(),
        )
    }

    private fun start(): Int = KitRunnerCommand("mydb", File(workspace, "mydb"), Constants.Kit.PHASE_START).call()

    private fun localToolCalls(): List<String> = File(workspace, "tool-calls.log").takeIf { it.isFile }?.readLines().orEmpty()

    private fun installCommand(config: KitConfig): KitInstallCommand {
        val vars = TemplateVariables.from(state = states.load(), kitName = "", storageSize = "").toMap()
        return KitInstallCommandFactory(vars)
            .build(config, InstallTemplateResolver.TemplateSource.Directory(templateDir))
            .commandSpec
            .userObject() as KitInstallCommand
    }

    private val helmInstallKit =
        KitConfig(name = "mydb", type = null, install = listOf(InstallStep.HelmRepo(name = "myrepo", url = "https://charts.example.com")))

    @Test
    fun `an EC2 + K3s kit start runs helm on the control node over SSH`() {
        states.replace(ec2State())
        writeKubeconfig()
        writeHelmKit()

        assertThat(start()).isZero()

        val hosts = argumentCaptor<Host>()
        val commands = argumentCaptor<String>()
        verify(remoteOps, atLeastOnce()).executeRemotely(hosts.capture(), commands.capture(), any(), any())
        assertThat(hosts.allValues.map { it.alias }).containsOnly("control0")
        assertThat(commands.allValues)
            .anyMatch { it.startsWith("KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG} helm list -n default") }
            .anyMatch {
                it == "KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG} helm repo add myrepo https://charts.example.com --force-update"
            }
        assertThat(localToolCalls()).isEmpty()
    }

    @Test
    fun `a K3s in Docker kit start runs helm locally through the wrappers, with no control node and no SSH`() {
        states.replace(localState(Environment.K3sDocker("/data")))
        writeKubeconfig()
        writeHelmKit()

        assertThat(start()).isZero()

        assertThat(localToolCalls()).contains(
            "helm list -n default -f ^mydb\$ -q",
            "helm repo add myrepo https://charts.example.com --force-update",
        )
        verifyNoInteractions(remoteOps)
    }

    @Test
    fun `a kubeconfig workspace kit start runs helm locally through the wrappers, with no control node and no SSH`() {
        states.replace(localState(Environment.Kubeconfig(File(tempDir, "brought.kubeconfig").absolutePath)))
        writeKubeconfig()
        writeHelmKit()

        assertThat(start()).isZero()

        assertThat(localToolCalls()).contains("helm repo add myrepo https://charts.example.com --force-update")
        verifyNoInteractions(remoteOps)
    }

    @Test
    fun `a K3s in Docker kit install runs its helm step locally, with no control node and no SSH`() {
        states.replace(localState(Environment.K3sDocker("/data")))
        writeKubeconfig()

        assertThat(installCommand(helmInstallKit).call()).isZero()

        assertThat(localToolCalls()).contains("helm repo add myrepo https://charts.example.com --force-update")
        assertThat(File(workspace, "mydb")).isDirectory()
        verifyNoInteractions(remoteOps)
    }

    @Test
    fun `a local kit install with no workspace kubeconfig fails before it writes the scaffold`() {
        states.replace(localState(Environment.K3sDocker("/data")))

        assertThatThrownBy { installCommand(helmInstallKit).call() }.hasMessageContaining("kubeconfig")

        assertThat(File(workspace, "mydb")).doesNotExist()
        assertThat(workspace.listFiles().orEmpty().map { it.name }).noneMatch { it.startsWith(".tmp-mydb") }
        assertThat(localToolCalls()).isEmpty()
    }

    @Test
    fun `an EC2 + K3s kit install with no control node fails before it writes the scaffold`() {
        states.replace(ec2State().copy(hosts = emptyMap()))
        writeKubeconfig()

        assertThatThrownBy { installCommand(helmInstallKit).call() }.hasMessageContaining("No control node found")

        assertThat(File(workspace, "mydb")).doesNotExist()
        verifyNoInteractions(remoteOps)
    }

    private companion object {
        /** Appends `<tool> <args>` to `<workspace>/tool-calls.log` and prints nothing, whichever tool it stands for. */
        val RECORDING_WRAPPER =
            """
            #!/bin/sh
            echo "${'$'}{0##*/} ${'$'}*" >> "${'$'}(dirname "${'$'}0")/../tool-calls.log"
            """.trimIndent() + "\n"
    }
}
