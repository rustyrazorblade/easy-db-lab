package com.rustyrazorblade.easydblab.configuration.environment

import com.rustyrazorblade.easydblab.configuration.BackendStorage
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Exposure
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.NetworkMode
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.Placement
import com.rustyrazorblade.easydblab.configuration.ProfileRegion
import com.rustyrazorblade.easydblab.configuration.ServicePublishing
import com.rustyrazorblade.easydblab.configuration.StoreCredentials
import com.rustyrazorblade.easydblab.configuration.region
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/** What each execution environment resolves to, field by field, and what recorded state adds to it. */
class ClusterContractTest {
    private fun stateFor(
        environment: Environment,
        capabilities: Set<NodeCapability>? = null,
        nodeLabels: Map<String, String> = emptyMap(),
    ) = ClusterState(
        name = "test",
        environment = environment,
        capabilities = capabilities,
        initConfig = InitConfig(region = "us-east-1", nodeLabels = nodeLabels),
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("table")
    fun `each environment resolves every contract field`(
        environment: Environment,
        expected: ClusterContract,
    ) {
        assertThat(ClusterContract.of(stateFor(environment))).isEqualTo(expected)
    }

    /** `up` and `down` read this instead of testing the variant; #1041 flips it for K3s in Docker. */
    @Test
    fun `only EC2 plus K3s has a provisioner`() {
        assertThat(
            listOf(Environment.Ec2K3s, Environment.K3sDocker("/d"), Environment.Kubeconfig("/k"))
                .associateWith { ClusterContract.of(stateFor(it)).hasProvisioner },
        ).containsExactlyEntriesOf(
            mapOf(Environment.Ec2K3s to true, Environment.K3sDocker("/d") to false, Environment.Kubeconfig("/k") to false),
        )
    }

    @Test
    fun `an AWS state with no recorded capabilities still yields the AWS set`() {
        val state = ClusterState(name = "test")

        assertThat(ClusterContract.of(state).nodeCapabilities).isEqualTo(NodeCapabilities.AWS)
        assertThat(NodeCapabilities.from(state)).isEqualTo(NodeCapabilities.AWS)
    }

    /**
     * Every backend still keeps its state in the node's host directories and names the object store,
     * whatever the environment; #1035 changes the store kind and the storage together when it renders a volume.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("environments")
    fun `the store kind agrees with the storage the backends are rendered with`(environment: Environment) {
        val contract = ClusterContract.of(stateFor(environment))

        assertThat(contract.storeKind).isEqualTo(StoreKind.ObjectStore)
        assertThat(contract.hosting.storage).isEqualTo(BackendStorage.HostPath)
    }

    /**
     * Only an EC2 node has an instance role. Elsewhere the contract says no store credentials are
     * provided (#1061 provisions them), rather than naming a role that does not exist.
     */
    @Test
    fun `only an EC2 cluster names the instance role as its store credentials`() {
        assertThat(environments().associateWith { ClusterContract.of(stateFor(it)).hosting.credentials })
            .containsExactlyEntriesOf(
                mapOf(
                    Environment.Ec2K3s to StoreCredentials.InstanceRole,
                    Environment.K3sDocker("/d") to StoreCredentials.NotProvided,
                    Environment.Kubeconfig("/k") to StoreCredentials.NotProvided,
                ),
            )
    }

    /**
     * Only an EC2 cluster has an AWS profile to fall back on. A local or brought cluster with no
     * recorded region (no init configuration at all) has no region, and asking it never loads the profile.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("environmentsWithoutAws")
    fun `a cluster with no AWS account never asks the profile for its region or datacenter`(environment: Environment) {
        val state = ClusterState(name = "test", environment = environment)
        val noProfile = ProfileRegion { error("a ${environment.displayName} workspace must not load the AWS profile") }

        assertThat(state.region(noProfile)).isEmpty()
        assertThat(ClusterContract.of(state).datacenter(noProfile)).isEqualTo("dc1")
    }

    @Test
    fun `an EC2 state with no initConfig takes its region from the profile`() {
        val state = ClusterState(name = "test")

        assertThat(state.region { "eu-west-1" }).isEqualTo("eu-west-1")
    }

    @Test
    fun `an EC2 state with no initConfig is a contract whose datacenter is the profile's region`() {
        val state = ClusterState(name = "test")

        assertThat(state.initConfig).isNull()
        assertThat(ClusterContract.of(state).datacenter { "eu-west-1" }).isEqualTo("eu-west-1")
    }

    @Test
    fun `an EC2 state that records a blank region takes its datacenter from the profile's region`() {
        val state = ClusterState(name = "test", initConfig = InitConfig(region = " "))

        assertThat(ClusterContract.of(state).datacenter { "eu-west-1" }).isEqualTo("eu-west-1")
    }

    @Test
    fun `an EC2 state that records no region takes its datacenter from the profile's region`() {
        val contract = ClusterContract.of(ClusterState(name = "test"))

        assertThat(contract.datacenter { "eu-west-1" }).isEqualTo("eu-west-1")
    }

    @Test
    fun `an EC2 state that records no region, with no profile to ask, fails instead of naming a blank datacenter`() {
        val contract = ClusterContract.of(ClusterState(name = "test"))

        assertThatThrownBy { contract.datacenter(ProfileRegion.NONE) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("records no region")
    }

    @Test
    fun `a recorded region is the datacenter, and the profile is never asked`() {
        val contract = ClusterContract.of(stateFor(Environment.Ec2K3s))

        assertThat(contract.datacenter { error("the profile must not be loaded") }).isEqualTo("us-east-1")
    }

    @Test
    fun `a recorded capability set wins over the declared one`() {
        val recorded = setOf(NodeCapability.PRIVILEGED)

        val contract = ClusterContract.of(stateFor(Environment.K3sDocker("/d"), capabilities = recorded))

        assertThat(contract.nodeCapabilities.present()).containsExactly(NodeCapability.PRIVILEGED)
    }

    /** aws-account is declared, never recorded: a recorded set can neither drop it nor add it. */
    @Test
    fun `the environment alone decides aws-account whatever set is recorded`() {
        val awsRecordedWithout = stateFor(Environment.Ec2K3s, capabilities = setOf(NodeCapability.S3))
        val localRecordedWith = stateFor(Environment.K3sDocker("/d"), capabilities = setOf(NodeCapability.AWS_ACCOUNT))

        assertThat(ClusterContract.of(awsRecordedWithout).nodeCapabilities.present())
            .containsExactly(NodeCapability.S3, NodeCapability.AWS_ACCOUNT)
        assertThat(NodeCapabilities.from(localRecordedWith).present()).isEmpty()
    }

    @Test
    fun `node labels from init are merged into every environment's placement`() {
        val labels = mapOf("pool" to "obs")

        val contract = ClusterContract.of(stateFor(Environment.Kubeconfig("/k"), nodeLabels = labels))

        assertThat(contract.hosting.placement.nodeSelector).isEqualTo(labels)
        assertThat(Placement.from(stateFor(Environment.Kubeconfig("/k"), nodeLabels = labels))).isEqualTo(contract.hosting.placement)
    }

    @Test
    fun `the hosting and placement helpers read the contract`() {
        val state = stateFor(Environment.K3sDocker("/d"))

        assertThat(ObservabilityHosting.from(state)).isEqualTo(ClusterContract.of(state).hosting)
        assertThat(Placement.from(state)).isEqualTo(ClusterContract.of(state).hosting.placement)
    }

    @Test
    fun `the state source resolves a default EC2 plus K3s contract when there is no state`(
        @TempDir tempDir: File,
    ) {
        val source = StateClusterContractSource(ClusterStateManager(File(tempDir, "state.json")))

        assertThat(source.current().environment).isEqualTo(Environment.Ec2K3s)
        assertThat(source.current().toolingForm).isEqualTo(ToolingForm.Ssh)
    }

    @Test
    fun `EC2 takes the recorded network mode and every other environment is direct whatever it records`() {
        assertThat(ClusterContract.of(stateFor(Environment.Ec2K3s).apply { networkMode = NetworkMode.Tailscale }).networkMode)
            .isEqualTo(NetworkMode.Tailscale)
        assertThat(environmentsWithoutAws()).allSatisfy { environment ->
            assertThat(ClusterContract.of(stateFor(environment).apply { networkMode = NetworkMode.Socks }).networkMode)
                .isEqualTo(NetworkMode.Direct)
        }
    }

    /** Only EC2 nodes are easy-db-lab's, with the NVMe the per-kit local volumes live on. */
    @Test
    fun `only an EC2 cluster defaults its kits to local volumes, any other takes the cluster's default class`() {
        assertThat(environments().associateWith { ClusterContract.of(stateFor(it)).kitStorageDefault })
            .containsExactlyEntriesOf(
                mapOf(
                    Environment.Ec2K3s to KitStorageDefault.LocalVolumes,
                    Environment.K3sDocker("/d") to KitStorageDefault.ClusterDefaultClass,
                    Environment.Kubeconfig("/k") to KitStorageDefault.ClusterDefaultClass,
                ),
            )
    }

    /**
     * A K3s in Docker or brought cluster is reached by a port-forward through its kubeconfig, whatever
     * the state records (a hand-written or older state reads as Socks). EC2 keeps what `init` recorded.
     */
    @Test
    fun `EC2 takes the recorded exposure and every other environment is port-forwarded whatever it records`() {
        assertThat(ClusterContract.of(stateFor(Environment.Ec2K3s).apply { exposure = Exposure.Direct }).exposure)
            .isEqualTo(Exposure.Direct)
        assertThat(environmentsWithoutAws()).allSatisfy { environment ->
            assertThat(
                Exposure.entries.map { recorded ->
                    ClusterContract.of(stateFor(environment).apply { exposure = recorded }).exposure
                },
            ).containsOnly(Exposure.PortForward)
        }
    }

    @Test
    fun `the state source reads the state on every call`(
        @TempDir tempDir: File,
    ) {
        val manager = ClusterStateManager(File(tempDir, "state.json"))
        val source = StateClusterContractSource(manager)
        manager.replace(stateFor(Environment.Ec2K3s))
        assertThat(source.current().networkMode).isEqualTo(NetworkMode.Socks)

        manager.replace(stateFor(Environment.K3sDocker("/d")))

        assertThat(source.current().networkMode).isEqualTo(NetworkMode.Direct)
    }

    /**
     * Until the kits take the cluster namespace (#1094 PR 2), a brought cluster keeps the EKS slice's
     * `default`, so the stack and the kits never split across two namespaces (design D10).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("environments")
    fun `with no recorded namespace every environment resolves default`(environment: Environment) {
        assertThat(ClusterContract.of(stateFor(environment)).namespace).isEqualTo("default")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("environments")
    fun `a recorded namespace wins in every environment`(environment: Environment) {
        val state = stateFor(environment).copy(initConfig = InitConfig(region = "us-east-1", namespace = "lab-a"))

        assertThat(ClusterContract.of(state).namespace).isEqualTo("lab-a")
    }

    /** The REPL and the server resolve the contract per command, so an `init --namespace` applies to the next one. */
    @Test
    fun `a namespace recorded after the source was built is seen on the next call`(
        @TempDir tempDir: File,
    ) {
        val manager = ClusterStateManager(File(tempDir, "state.json"))
        val source = StateClusterContractSource(manager)
        manager.replace(stateFor(Environment.Ec2K3s))
        assertThat(source.current().namespace).isEqualTo("default")

        manager.replace(stateFor(Environment.Ec2K3s).copy(initConfig = InitConfig(namespace = "lab-a")))

        assertThat(source.current().namespace).isEqualTo("lab-a")
    }

    companion object {
        @JvmStatic
        fun environments(): List<Environment> = listOf(Environment.Ec2K3s, Environment.K3sDocker("/d"), Environment.Kubeconfig("/k"))

        @JvmStatic
        fun environmentsWithoutAws(): List<Environment> = listOf(Environment.K3sDocker("/d"), Environment.Kubeconfig("/k"))

        @JvmStatic
        fun table(): List<Arguments> = listOf(ec2K3sRow(), k3sDockerRow(), kubeconfigRow())

        private fun ec2K3sRow(): Arguments =
            Arguments.of(
                Environment.Ec2K3s,
                ClusterContract(
                    environment = Environment.Ec2K3s,
                    hasProvisioner = true,
                    localDataDirectory = null,
                    toolingForm = ToolingForm.Ssh,
                    nodeAccessForm = NodeAccessForm.Ssh,
                    storeKind = StoreKind.ObjectStore,
                    networkMode = NetworkMode.Socks,
                    exposure = Exposure.Socks,
                    kitStorageDefault = KitStorageDefault.LocalVolumes,
                    nodeCapabilities = NodeCapabilities.AWS,
                    hosting = ObservabilityHosting.AWS,
                    namespace = "default",
                    recordedDatacenter = "us-east-1",
                    clientNetworkOrigin = ClientNetworkOrigin.BuiltNetworks,
                ),
            )

        private fun k3sDockerRow(): Arguments =
            Arguments.of(
                Environment.K3sDocker("/d"),
                ClusterContract(
                    environment = Environment.K3sDocker("/d"),
                    hasProvisioner = false,
                    localDataDirectory = "/d",
                    toolingForm = ToolingForm.Local,
                    nodeAccessForm = NodeAccessForm.ContainerExec,
                    storeKind = StoreKind.ObjectStore,
                    networkMode = NetworkMode.Direct,
                    exposure = Exposure.PortForward,
                    kitStorageDefault = KitStorageDefault.ClusterDefaultClass,
                    nodeCapabilities = NodeCapabilities.LOCAL_DOCKER,
                    hosting =
                        ObservabilityHosting(
                            Placement.CONTROL_NODE,
                            BackendStorage.HostPath,
                            StoreCredentials.NotProvided,
                            ServicePublishing.ClusterIp,
                        ),
                    namespace = "default",
                    recordedDatacenter = "dc1",
                    clientNetworkOrigin = ClientNetworkOrigin.Recorded,
                ),
            )

        private fun kubeconfigRow(): Arguments =
            Arguments.of(
                Environment.Kubeconfig("/k"),
                ClusterContract(
                    environment = Environment.Kubeconfig("/k"),
                    hasProvisioner = false,
                    localDataDirectory = null,
                    toolingForm = ToolingForm.Local,
                    nodeAccessForm = NodeAccessForm.None,
                    storeKind = StoreKind.ObjectStore,
                    networkMode = NetworkMode.Direct,
                    exposure = Exposure.PortForward,
                    kitStorageDefault = KitStorageDefault.ClusterDefaultClass,
                    nodeCapabilities = NodeCapabilities.NONE,
                    hosting =
                        ObservabilityHosting(
                            Placement.ANY_NODE,
                            BackendStorage.HostPath,
                            StoreCredentials.NotProvided,
                            ServicePublishing.ClusterIp,
                        ),
                    namespace = "default",
                    recordedDatacenter = "dc1",
                    clientNetworkOrigin = ClientNetworkOrigin.Recorded,
                ),
            )
    }
}
