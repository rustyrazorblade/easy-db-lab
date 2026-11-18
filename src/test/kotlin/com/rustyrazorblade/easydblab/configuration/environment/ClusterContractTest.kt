package com.rustyrazorblade.easydblab.configuration.environment

import com.rustyrazorblade.easydblab.configuration.BackendStorage
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
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
        versions = mutableMapOf(),
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
        val state = ClusterState(name = "test", versions = mutableMapOf())

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
        val state = ClusterState(name = "test", versions = mutableMapOf(), environment = environment)
        val noProfile = ProfileRegion { error("a ${environment.displayName} workspace must not load the AWS profile") }

        assertThat(state.region(noProfile)).isEmpty()
        assertThat(ClusterContract.of(state).datacenter(noProfile)).isEqualTo("dc1")
    }

    @Test
    fun `an EC2 state with no initConfig takes its region from the profile`() {
        val state = ClusterState(name = "test", versions = mutableMapOf())

        assertThat(state.region { "eu-west-1" }).isEqualTo("eu-west-1")
    }

    @Test
    fun `an EC2 state with no initConfig is a contract whose datacenter is the profile's region`() {
        val state = ClusterState(name = "test", versions = mutableMapOf())

        assertThat(state.initConfig).isNull()
        assertThat(ClusterContract.of(state).datacenter { "eu-west-1" }).isEqualTo("eu-west-1")
    }

    @Test
    fun `an EC2 state that records a blank region takes its datacenter from the profile's region`() {
        val state = ClusterState(name = "test", versions = mutableMapOf(), initConfig = InitConfig(region = " "))

        assertThat(ClusterContract.of(state).datacenter { "eu-west-1" }).isEqualTo("eu-west-1")
    }

    @Test
    fun `an EC2 state that records no region takes its datacenter from the profile's region`() {
        val contract = ClusterContract.of(ClusterState(name = "test", versions = mutableMapOf()))

        assertThat(contract.datacenter { "eu-west-1" }).isEqualTo("eu-west-1")
    }

    @Test
    fun `an EC2 state that records no region, with no profile to ask, fails instead of naming a blank datacenter`() {
        val contract = ClusterContract.of(ClusterState(name = "test", versions = mutableMapOf()))

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
    fun `the state source reads the state on every call`(
        @TempDir tempDir: File,
    ) {
        val manager = ClusterStateManager(File(tempDir, "state.json"))
        val source = StateClusterContractSource(manager)
        manager.save(stateFor(Environment.Ec2K3s))
        assertThat(source.current().networkMode).isEqualTo(NetworkMode.Tunnel)

        manager.save(stateFor(Environment.K3sDocker("/d")))

        assertThat(source.current().networkMode).isEqualTo(NetworkMode.Direct)
    }

    companion object {
        @JvmStatic
        fun environments(): List<Environment> = listOf(Environment.Ec2K3s, Environment.K3sDocker("/d"), Environment.Kubeconfig("/k"))

        @JvmStatic
        fun environmentsWithoutAws(): List<Environment> = listOf(Environment.K3sDocker("/d"), Environment.Kubeconfig("/k"))

        @JvmStatic
        fun table(): List<Arguments> =
            listOf(
                Arguments.of(
                    Environment.Ec2K3s,
                    ClusterContract(
                        environment = Environment.Ec2K3s,
                        hasProvisioner = true,
                        localDataDirectory = null,
                        toolingForm = ToolingForm.Ssh,
                        nodeAccessForm = NodeAccessForm.Ssh,
                        storeKind = StoreKind.ObjectStore,
                        networkMode = NetworkMode.Tunnel,
                        nodeCapabilities = NodeCapabilities.AWS,
                        hosting = ObservabilityHosting.AWS,
                        namespace = "default",
                        recordedDatacenter = "us-east-1",
                    ),
                ),
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
                    ),
                ),
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
                        nodeCapabilities = NodeCapabilities.NONE,
                        hosting =
                            ObservabilityHosting(
                                Placement.ANY_NODE,
                                BackendStorage.HostPath,
                                StoreCredentials.NotProvided,
                                ServicePublishing.ClusterIp,
                            ),
                        namespace = "easy-db-lab",
                        recordedDatacenter = "dc1",
                    ),
                ),
            )
    }
}
