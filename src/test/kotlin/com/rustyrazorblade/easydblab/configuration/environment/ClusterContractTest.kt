package com.rustyrazorblade.easydblab.configuration.environment

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.Placement
import org.assertj.core.api.Assertions.assertThat
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

    @Test
    fun `an AWS state with no recorded capabilities still yields the AWS set`() {
        val state = ClusterState(name = "test", versions = mutableMapOf())

        assertThat(ClusterContract.of(state).nodeCapabilities).isEqualTo(NodeCapabilities.AWS)
        assertThat(NodeCapabilities.from(state)).isEqualTo(NodeCapabilities.AWS)
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
        assertThat(Placement.from(state).publishOnNode).isFalse()
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
        private val localPlacement = Placement.CONTROL_NODE.copy(publishOnNode = false)

        @JvmStatic
        fun table(): List<Arguments> =
            listOf(
                Arguments.of(
                    Environment.Ec2K3s,
                    ClusterContract(
                        environment = Environment.Ec2K3s,
                        toolingForm = ToolingForm.Ssh,
                        nodeAccessForm = NodeAccessForm.Ssh,
                        storeKind = StoreKind.ObjectStore,
                        networkMode = NetworkMode.Tunnel,
                        nodeCapabilities = NodeCapabilities.AWS,
                        hosting = ObservabilityHosting.AWS,
                        namespace = "default",
                        datacenter = "us-east-1",
                    ),
                ),
                Arguments.of(
                    Environment.K3sDocker("/d"),
                    ClusterContract(
                        environment = Environment.K3sDocker("/d"),
                        toolingForm = ToolingForm.Local,
                        nodeAccessForm = NodeAccessForm.ContainerExec,
                        storeKind = StoreKind.Volume,
                        networkMode = NetworkMode.Direct,
                        nodeCapabilities = NodeCapabilities.LOCAL_DOCKER,
                        hosting = ObservabilityHosting.AWS.copy(placement = localPlacement),
                        namespace = "default",
                        datacenter = "dc1",
                    ),
                ),
                Arguments.of(
                    Environment.Kubeconfig("/k"),
                    ClusterContract(
                        environment = Environment.Kubeconfig("/k"),
                        toolingForm = ToolingForm.Local,
                        nodeAccessForm = NodeAccessForm.None,
                        storeKind = StoreKind.Volume,
                        networkMode = NetworkMode.Direct,
                        nodeCapabilities = NodeCapabilities.NONE,
                        hosting = ObservabilityHosting.AWS.copy(placement = Placement.ANY_NODE),
                        namespace = "easy-db-lab",
                        datacenter = "dc1",
                    ),
                ),
            )
    }
}
