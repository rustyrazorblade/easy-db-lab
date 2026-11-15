package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.BackendStorage
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.Placement
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.StoreCredentials
import io.fabric8.kubernetes.api.model.Quantity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ClusterConfigDataTest {
    private val control = ClusterHost("1.2.3.4", "10.0.0.5", "control0", "us-west-2a")

    private val state =
        ClusterState(
            name = "lab",
            versions = mutableMapOf(),
            clusterId = "abc123",
            s3Bucket = "acct-bucket",
            initConfig = InitConfig(tenant = "acme", region = "us-east-1"),
            hosts = mapOf(ServerType.Control to listOf(control)),
        )

    /** A cluster the user brought: no control host in state, nothing published on a node. */
    private val broughtCluster = state.copy(hosts = emptyMap())
    private val offAws =
        ObservabilityHosting(Placement.ANY_NODE, BackendStorage.RetainedClaim("retained", Quantity("1Gi")), StoreCredentials.InstanceRole)

    @Test
    fun `publishes the tenant so every producer reads it from one place`() {
        val data = ClusterConfigData.of(state, ObservabilityHosting.AWS, "us-west-2")

        assertThat(data).containsEntry("tenant", "acme")
    }

    @Test
    fun `a cluster without a tenant publishes the default tenant`() {
        val data = ClusterConfigData.of(state.copy(initConfig = null), ObservabilityHosting.AWS, "us-west-2")

        assertThat(data).containsEntry("tenant", "default")
    }

    @Test
    fun `on AWS the contents are exactly today's, the control node's private IP included`() {
        val data = ClusterConfigData.of(state, ObservabilityHosting.AWS, "us-west-2")

        assertThat(data).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "control_node_ip" to "10.0.0.5",
                "store_bucket" to "acct-bucket",
                "store_prefix" to "",
                "store_region" to "us-east-1",
                "store_endpoint" to "s3.us-east-1.amazonaws.com",
                "traces_store_prefix" to "tempo",
                "metrics_store_prefix" to "mimir",
                "logs_store_prefix" to "loki",
                "cluster_name" to "lab-abc123",
                "tenant" to "acme",
                "capabilities" to NodeCapability.entries.joinToString(",") { it.serialName },
            ),
        )
    }

    @Test
    fun `where nothing is published on a node there is no node IP, and no control host is needed`() {
        val data = ClusterConfigData.of(broughtCluster, offAws, "us-west-2")

        assertThat(data).doesNotContainKey("control_node_ip").containsEntry("cluster_name", "lab-abc123")
    }

    @Test
    fun `on AWS a state without a control node fails, naming the callers that need its IP`() {
        assertThatThrownBy { ClusterConfigData.of(broughtCluster, ObservabilityHosting.AWS, "us-west-2") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no control node")
            .hasMessageContaining("redirected clusters")
    }

    @Test
    fun `publishes the present capabilities in enum order, without a removed one`() {
        val recorded = setOf(NodeCapability.NODE_SSH, NodeCapability.S3, NodeCapability.HOST_PATH)

        val data = ClusterConfigData.of(state.copy(capabilities = recorded), ObservabilityHosting.AWS, "us-west-2")

        assertThat(data).containsEntry("capabilities", "s3,host-path,node-ssh")
    }

    @Test
    fun `the same recorded set always publishes the same value`() {
        val first =
            ClusterConfigData.of(
                state.copy(capabilities = linkedSetOf(NodeCapability.HOST_PATH, NodeCapability.S3)),
                ObservabilityHosting.AWS,
                "us-west-2",
            )
        val second =
            ClusterConfigData.of(
                state.copy(capabilities = linkedSetOf(NodeCapability.S3, NodeCapability.HOST_PATH)),
                ObservabilityHosting.AWS,
                "us-west-2",
            )

        assertThat(first["capabilities"]).isEqualTo(second["capabilities"])
    }

    @Test
    fun `a state with no recorded set publishes every capability`() {
        val data = ClusterConfigData.of(state, ObservabilityHosting.AWS, "us-west-2")

        assertThat(data).containsEntry("capabilities", NodeCapability.entries.joinToString(",") { it.serialName })
    }

    @Test
    fun `publishes the store in neutral keys, with the S3 endpoint of the cluster's region`() {
        val data = ClusterConfigData.of(state, ObservabilityHosting.AWS, "us-west-2")

        assertThat(data)
            .containsEntry("store_bucket", "acct-bucket")
            .containsEntry("store_prefix", "")
            .containsEntry("store_region", "us-east-1")
            .containsEntry("store_endpoint", "s3.us-east-1.amazonaws.com")
            .doesNotContainKeys("aws_region", "s3_bucket", "metrics_s3_prefix", "logs_s3_prefix", "traces_s3_prefix")
    }
}
