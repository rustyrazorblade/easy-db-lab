package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.DockerClientInterface
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.ClientNetworks
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.Exposure
import com.rustyrazorblade.easydblab.configuration.NetworkMode
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.RoleSelectors
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.configuration.environment.StateClusterContractSource
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.providers.docker.DockerClientProvider
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
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import picocli.CommandLine
import java.io.File
import java.net.ConnectException
import java.net.SocketException

/**
 * `init --local` on a machine with no AWS configuration: the profile directory is empty, loading the
 * profile fails the test, and so does any EC2 call. The state is written to and read back from the
 * real `state.json`.
 */
class InitLocalTest : BaseKoinTest() {
    @TempDir
    lateinit var emptyProfile: File

    private val events = mutableListOf<Event>()

    /** What the Docker daemon reports as its architecture (`docker info`'s `Architecture`). */
    private var dockerArchitecture = "x86_64"

    /** When set, the Docker daemon cannot be reached and asking for its architecture throws this. */
    private var dockerUnavailable: RuntimeException? = null

    private val workspace: File get() = context.workingDirectory
    private val stateFile: File get() = File(workspace, "state.json")

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { ClusterStateManager(File(get<Context>().workingDirectory, "state.json")) }
                single { TemplateService(get(), get()) }
                single { UserConfigProvider(emptyProfile) }
                single<User> { error("init --local must not load the AWS profile") }
                // Init resolves both lazily (class-level `by inject()`); resolving either fails the test.
                single<EC2InstanceService> { error("init --local must not build an EC2 client, which loads the profile for its region") }
                single<DockerClientProvider> {
                    val client =
                        mock<DockerClientInterface> {
                            on { serverArchitecture() } doAnswer
                                { dockerUnavailable?.let { throw it } ?: dockerArchitecture }
                        }
                    object : DockerClientProvider {
                        override fun getDockerClient(): DockerClientInterface = client
                    }
                }
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
    fun `a namespace is accepted and recorded in a local workspace`() {
        parsed("--local", "--namespace", "lab-a").execute()

        assertThat(state().initConfig?.namespace).isEqualTo("lab-a")
    }

    @Test
    fun `the default data directory is observability-data under the workspace, recorded absolute`() {
        parsed("--local").execute()

        val expected = File(workspace, Constants.Local.DEFAULT_DATA_DIRECTORY).absoluteFile.normalize().path
        assertThat(state().environment).isEqualTo(Environment.K3sDocker(expected))
        assertThat(File(expected).isAbsolute).isTrue()
    }

    /**
     * Like every other path the user types (`cql --file`, `grafana install`, `kit source add`), a relative
     * `--data-dir` names a directory relative to the process's current directory, not to the workspace the
     * context names. The test's workspace is a temporary directory, so the two differ here.
     */
    @Test
    fun `a relative data directory is resolved against the process current directory and recorded absolute`() {
        parsed("--local", "--data-dir", "../shared-data").execute()

        val expected =
            File(System.getProperty("user.dir"))
                .toPath()
                .resolve("../shared-data")
                .toAbsolutePath()
                .normalize()
                .toString()
        assertThat(workspace.absoluteFile).isNotEqualTo(File(System.getProperty("user.dir")).absoluteFile)
        assertThat(state().environment).isEqualTo(Environment.K3sDocker(expected))
    }

    @Test
    fun `an absolute data directory is recorded as given, normalized`() {
        val absolute = File(workspace, "elsewhere/../obs").absolutePath

        parsed("--local", "--data-dir", absolute).execute()

        assertThat(state().environment).isEqualTo(Environment.K3sDocker(File(workspace, "obs").absolutePath))
    }

    @Test
    fun `it records port-forward exposure, no Tailscale, Flannel, no region, the Docker daemon's architecture and the local set`() {
        parsed("--local", "--capability", "host-path=false").execute()

        val state = state()
        assertThat(state.exposure).isEqualTo(Exposure.PortForward)
        assertThat(state.networkMode).isEqualTo(NetworkMode.Direct)
        assertThat(state.capabilities).containsExactlyInAnyOrder(NodeCapability.PRIVILEGED, NodeCapability.HOST_NETWORK)
        val initConfig = requireNotNull(state.initConfig)
        assertThat(initConfig.cni).isEqualTo(CniMode.Flannel)
        assertThat(initConfig.region).isEmpty()
        // The harness's Docker daemon reports x86_64.
        assertThat(listOf(initConfig.dbArch, initConfig.appArch, initConfig.controlArch)).containsOnly(Arch.AMD64.name)
    }

    /** These travel further than state.json: the tenant into X-Scope-OrgID, the labels into placement. */
    @Test
    fun `it records the node counts, tenant, node labels and telemetry redirect it was given`() {
        parsed(
            "--local",
            "--db.count=4",
            "--app.count=2",
            "--tenant=team_a",
            "--node-label=pool=obs",
            "--redirect-telemetry=collector.example.com",
        ).execute()

        val initConfig = requireNotNull(state().initConfig)
        assertThat(initConfig.cassandraInstances).isEqualTo(4)
        assertThat(initConfig.stressInstances).isEqualTo(2)
        assertThat(initConfig.tenant).isEqualTo("team_a")
        assertThat(initConfig.nodeLabels).isEqualTo(mapOf("pool" to "obs"))
        assertThat(initConfig.telemetryRedirect).isEqualTo(TelemetryRedirect.fromBaseHost("collector.example.com"))
        val initialized = events.filterIsInstance<Event.Setup.LocalWorkspaceInitialized>().single()
        val dataDir = File(workspace, Constants.Local.DEFAULT_DATA_DIRECTORY).absoluteFile.normalize().path
        assertThat(initialized).isEqualTo(Event.Setup.LocalWorkspaceInitialized(dataDir = dataDir, dbCount = 4, appCount = 2))
    }

    /** No local provisioner exists yet, so `--up` would write a workspace and then fail in `up`. */
    @Test
    fun `--local --up is refused before anything is written`() {
        val command = parsed("--local", "--up")
        val before = workspace.list().orEmpty().toSortedSet()

        assertThatThrownBy { command.execute() }.isInstanceOf(CommandFailedException::class.java)

        val conflict = events.filterIsInstance<Event.Setup.InitOptionConflict>().single()
        assertThat(conflict.option).isEqualTo("--up")
        assertThat(conflict.toDisplayString())
            .startsWith("Error: --up cannot be used with --local")
            .endsWith("Nothing was written.")
        assertThat(workspace.list().orEmpty().toSortedSet()).isEqualTo(before)
    }

    /** Refusal is by what was typed (picocli's parse result), never by comparing a value with its default. */
    @Test
    fun `an exported AMI or instance type default does not refuse --local`() {
        val command = Init()
        CommandLine(command).parseArgs("--local")
        // As if EASY_DB_LAB_AMI and EASY_DB_LAB_INSTANCE_TYPE were exported: the field defaults read them.
        command.ami = "ami-from-env"
        command.instanceType = "r5.from-env"

        command.execute()

        assertThat(events.filterIsInstance<Event.Setup.LocalOptionsRefused>()).isEmpty()
        assertThat(state().environment).isInstanceOf(Environment.K3sDocker::class.java)
    }

    @Test
    fun `an explicit AMI equal to its default is still refused`() {
        val command = parsed("--local", "--ami=")

        assertThatThrownBy { command.execute() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(events.filterIsInstance<Event.Setup.LocalOptionsRefused>().single().options).containsExactly("--ami")
    }

    /**
     * The nodes are containers, so their architecture is the Docker daemon's, not this JVM's: an x86_64
     * JVM under Rosetta on Apple Silicon reports x86_64 while Docker Desktop's VM runs arm64.
     */
    @Test
    fun `the recorded architecture is the Docker daemon's, not the JVM's`() {
        dockerArchitecture = "aarch64"

        parsed("--local").execute()

        val initConfig = requireNotNull(state().initConfig)
        assertThat(listOf(initConfig.dbArch, initConfig.appArch, initConfig.controlArch)).containsOnly(Arch.ARM64.name)
    }

    @Test
    fun `an unsupported Docker architecture fails before --clean removes anything`() {
        parsed("--local").execute()
        val before = workspace.list().orEmpty().toSortedSet()
        dockerArchitecture = "sparc"

        assertThatThrownBy { parsed("--local", "--clean").execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("sparc")

        assertThat(workspace.list().orEmpty().toSortedSet()).isEqualTo(before)
        assertThat(state().environment).isInstanceOf(Environment.K3sDocker::class.java)
    }

    /**
     * The local nodes keep their `type=<role>` labels, like AWS (kit-host-independence spec). Their
     * networks are Docker's and the pods', which easy-db-lab does not know at `init`, so with no
     * `--client-network` nothing is recorded and kit variables fail, naming the option, instead of
     * trusting the AWS VPC default.
     */
    @Test
    fun `with no --role-nodes or --client-network it records the owned roles and no client network, and warns`() {
        parsed("--local").execute()

        val state = state()
        assertThat(state.roleSelectors).isEqualTo(RoleSelectors.OWNED_NODES)
        assertThat(state.clientNetworks).isNull()
        assertThat(events).contains(Event.Setup.NoClientNetworksRecorded("K3s in Docker"))
        assertThat(events.filterIsInstance<Event.Setup.ClientNetworksRecorded>()).isEmpty()
        assertThatThrownBy { ClientNetworks.from(state) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("K3s in Docker")
            .hasMessageContaining(ClientNetworks.OPTION)
            .hasMessageNotContaining(Constants.Vpc.DEFAULT_CIDR)
    }

    @Test
    fun `--client-network values are the only client networks a local workspace records`() {
        parsed("--local", "--client-network", "172.18.0.0/16", "--client-network", "10.42.0.0/16").execute()

        val state = state()
        assertThat(state.clientNetworks).containsExactly("172.18.0.0/16", "10.42.0.0/16")
        assertThat(ClientNetworks.from(state).cidrs.map { it.value })
            .containsExactly("172.18.0.0/16", "10.42.0.0/16")
            .doesNotContain(Constants.Vpc.DEFAULT_CIDR, Constants.K3s.CLUSTER_CIDR)
        assertThat(events).contains(Event.Setup.ClientNetworksRecorded(listOf("172.18.0.0/16", "10.42.0.0/16")))
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

    /** Reported like the AWS-only refusal: a typed event the user reads, then a failure the executor does not print again. */
    @Test
    fun `--data-dir without --local names both options and writes nothing`() {
        val command = parsed("--data-dir", "/tmp/x")

        assertThatThrownBy { command.execute() }.isInstanceOf(CommandFailedException::class.java)

        val conflict = events.filterIsInstance<Event.Setup.InitOptionConflict>().single()
        assertThat(conflict).isEqualTo(Event.Setup.InitOptionConflict("--data-dir", "requires --local"))
        assertThat(conflict.toDisplayString()).isEqualTo("Error: --data-dir requires --local. Nothing was written.")
        assertThat(conflict.isError()).isTrue()
        assertThat(stateFile).doesNotExist()
    }

    /** A command built in code has no parse result, so the AWS-only refusal could not see what was typed: it fails instead. */
    @Test
    fun `a --local init that was never parsed fails rather than skip the AWS-only refusal`() {
        val command = Init().apply { local = true }

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("parsed")
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

    /**
     * The architecture comes from `docker info`, so with no daemon `init --local` cannot build its state.
     * It reports that as its own event and fails before `--clean` runs or anything is written.
     */
    @Test
    fun `with Docker not running it reports a typed event, fails and writes nothing`() {
        parsed("--local").execute()
        val before = workspace.walkTopDown().associate { it.path to (if (it.isFile) it.readText() else "") }
        dockerUnavailable = RuntimeException("java.net.SocketException: No such file or directory", SocketException("docker.sock"))

        assertThatThrownBy { parsed("--local", "--clean").execute() }
            .isInstanceOf(CommandFailedException::class.java)

        val event = events.filterIsInstance<Event.Setup.LocalDockerUnavailable>().single()
        assertThat(event.isError()).isTrue()
        assertThat(event.toDisplayString()).contains("Docker").contains("docker.sock").contains("Nothing was written")
        assertThat(workspace.walkTopDown().associate { it.path to (if (it.isFile) it.readText() else "") }).isEqualTo(before)
    }

    @Test
    fun `with Docker not running in a new directory no workspace file is created`() {
        val before = workspace.walkTopDown().map { it.path }.toSet()
        dockerUnavailable = RuntimeException("Connection refused", ConnectException("Connection refused"))

        assertThatThrownBy { parsed("--local").execute() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(workspace.walkTopDown().map { it.path }.toSet()).isEqualTo(before)
        assertThat(stateFile).doesNotExist()
        assertThat(events.filterIsInstance<Event.Setup.LocalDockerUnavailable>()).hasSize(1)
    }

    /** The owner's data: neither `clean` nor `init --clean` may remove a populated `--data-dir`, inside the workspace or not. */
    @Test
    fun `clean and init --clean leave a populated --data-dir untouched`() {
        val inside = File(workspace, "obs").apply { mkdirs() }
        File(inside, "blocks/01").apply { parentFile.mkdirs() }.writeText("metrics")
        val dataDir = inside.absolutePath
        parsed("--local", "--data-dir", dataDir).execute()

        Clean().execute()
        assertThat(File(inside, "blocks/01")).hasContent("metrics")
        parsed("--local", "--data-dir", dataDir).execute()
        parsed("--local", "--data-dir", dataDir, "--clean").execute()

        assertThat(File(inside, "blocks/01")).hasContent("metrics")
        assertThat(state().environment).isEqualTo(Environment.K3sDocker(inside.absoluteFile.normalize().path))
    }
}
