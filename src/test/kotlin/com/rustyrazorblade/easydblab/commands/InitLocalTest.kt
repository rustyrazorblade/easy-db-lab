package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.Exposure
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.configuration.environment.StateClusterContractSource
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import com.rustyrazorblade.easydblab.services.EnvironmentRequirements
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.mock
import picocli.CommandLine
import java.io.File

/**
 * `init --local` on a machine with no AWS configuration: the profile directory is empty, loading the
 * profile fails the test, and so does any EC2 call. The state is written to and read back from the
 * real `state.json`.
 */
class InitLocalTest : BaseKoinTest() {
    @TempDir
    lateinit var emptyProfile: File

    private val events = mutableListOf<Event>()

    private val workspace: File get() = context.workingDirectory
    private val stateFile: File get() = File(workspace, "state.json")

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { ClusterStateManager(File(get<Context>().workingDirectory, "state.json")) }
                single { TemplateService(get()) }
                single { UserConfigProvider(emptyProfile) }
                single<User> { error("init --local must not load the AWS profile") }
                single<EC2InstanceService> { mock(defaultAnswer = { error("init --local must make no AWS call") }) }
            },
        )

    @BeforeEach
    fun captureEvents() {
        get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
    }

    private fun parsed(vararg args: String): Init = Init().also { CommandLine(it).parseArgs(*args) }

    private fun state() = ClusterStateManager(stateFile).load()

    @Test
    fun `with no AWS configuration it succeeds, needs no profile and records K3s in Docker`() {
        assertThat(get<UserConfigProvider>().isSetup()).isFalse()
        val command = parsed("--local")

        assertThat(EnvironmentRequirements(StateClusterContractSource(get())).needsProfile(command)).isFalse()
        command.execute()

        assertThat(state().environment).isInstanceOf(Environment.K3sDocker::class.java)
        assertThat(events.filterIsInstance<Event.Setup.LocalWorkspaceInitialized>()).hasSize(1)
    }

    @Test
    fun `a plain init still needs the profile`() {
        assertThat(EnvironmentRequirements(StateClusterContractSource(get())).needsProfile(parsed())).isTrue()
    }

    @Test
    fun `the default data directory is observability-data under the workspace, recorded absolute`() {
        parsed("--local").execute()

        val expected = File(workspace, Constants.Local.DEFAULT_DATA_DIRECTORY).absoluteFile.normalize().path
        assertThat(state().environment).isEqualTo(Environment.K3sDocker(expected))
        assertThat(File(expected).isAbsolute).isTrue()
    }

    @Test
    fun `a relative data directory is resolved against the current directory and recorded absolute`() {
        parsed("--local", "--data-dir", "../shared-data").execute()

        val expected = File(workspace.absoluteFile.parentFile, "shared-data").path
        assertThat(state().environment).isEqualTo(Environment.K3sDocker(expected))
    }

    @Test
    fun `it records port-forward exposure, no Tailscale, Flannel, no region, the host's architecture and the local set`() {
        parsed("--local", "--capability", "host-path=false").execute()

        val state = state()
        val host = Arch.ofHost().name
        assertThat(state.exposure).isEqualTo(Exposure.PortForward)
        assertThat(state.tailscaleActive).isFalse()
        assertThat(state.capabilities).containsExactlyInAnyOrder(NodeCapability.PRIVILEGED, NodeCapability.HOST_NETWORK)
        val initConfig = requireNotNull(state.initConfig)
        assertThat(initConfig.cni).isEqualTo(CniMode.Flannel)
        assertThat(initConfig.region).isEmpty()
        assertThat(listOf(initConfig.dbArch, initConfig.appArch, initConfig.controlArch)).containsOnly(host)
    }

    @Test
    fun `without overrides the local Docker set is recorded`() {
        parsed("--local").execute()

        assertThat(state().capabilities).isEqualTo(NodeCapabilities.LOCAL_DOCKER.present().toSet())
    }

    @Test
    fun `it writes direct mode to the proxy env file`() {
        parsed("--local").execute()

        assertThat(ProxyEnvFile(workspace).file.readLines()).contains("EDL_NETWORK_MODE=direct")
    }

    @Test
    fun `the wrappers of a local workspace set no proxy variable and need no tunnel`(
        @TempDir realBin: File,
    ) {
        parsed("--local").execute()
        // `init` writes no bin/; the wrappers come from the workspace preparation every cluster gets.
        ToolWrapperInstaller().install(workspace)
        File(workspace, Constants.K3s.LOCAL_KUBECONFIG).writeText("apiVersion: v1\nkind: Config\n")
        File(realBin, "kubectl").apply {
            writeText("#!/bin/sh\nenv | grep -i '_proxy=' || true\necho ran\n")
            setExecutable(true)
        }
        val path = listOf(File(workspace, Constants.ToolWrappers.DIRECTORY), realBin).joinToString(File.pathSeparator) { it.absolutePath }
        val process =
            ProcessBuilder("/bin/sh", "-c", "kubectl get ns")
                .directory(workspace)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment()["PATH"] = "$path${File.pathSeparator}/usr/bin${File.pathSeparator}/bin"
                }.start()
        val output = process.inputStream.bufferedReader().readText()

        assertThat(process.waitFor()).withFailMessage(output).isZero()
        assertThat(output.lines()).containsExactly("ran", "")
        assertThat(output).doesNotContain("socks start")
    }

    @Test
    fun `AWS-only options are refused by name and nothing is written`() {
        val command = parsed("--local", "--instance", "r5.xlarge", "--azs", "a,b")
        val before = workspace.list().orEmpty().toList()

        assertThatThrownBy { command.execute() }.isInstanceOf(CommandFailedException::class.java)

        val refused = events.filterIsInstance<Event.Setup.LocalOptionsRefused>().single()
        assertThat(refused.options).containsExactly("--instance", "--azs")
        assertThat(refused.toDisplayString()).contains("--instance", "--azs", "--local")
        assertThat(workspace.list().orEmpty().toList()).containsExactlyInAnyOrderElementsOf(before)
    }

    @Test
    fun `every AWS-only option family is refused`() {
        val command =
            parsed(
                "--local",
                "--db.instance-type=r5.xlarge",
                "--ebs.type=gp3",
                "--spark.enable",
                "--opensearch.enable",
                "--vpc=vpc-1",
                "--cidr=10.2.0.0/16",
                "--cni=flannel",
                "--ami=ami-1",
                "--open",
            )

        assertThatThrownBy { command.execute() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(events.filterIsInstance<Event.Setup.LocalOptionsRefused>().single().options).containsExactly(
            "--db.instance-type",
            "--ebs.type",
            "--spark.enable",
            "--opensearch.enable",
            "--vpc",
            "--cidr",
            "--cni",
            "--ami",
            "--open",
        )
        assertThat(stateFile).doesNotExist()
    }

    @Test
    fun `--data-dir without --local is a usage error`() {
        val command = parsed("--data-dir", "/tmp/x")

        assertThatThrownBy { command.execute() }
            .isInstanceOf(CommandLine.ParameterException::class.java)
            .hasMessageContaining("--data-dir requires --local")
        assertThat(stateFile).doesNotExist()
    }

    @Test
    fun `an existing data directory does not stop init, and clean keeps it`() {
        val data = File(workspace, Constants.Local.DEFAULT_DATA_DIRECTORY).apply { mkdirs() }
        File(data, "chunk").writeText("owner's data")

        parsed("--local").execute()
        parsed("--local", "--clean").execute()

        assertThat(File(data, "chunk")).hasContent("owner's data")
    }
}
