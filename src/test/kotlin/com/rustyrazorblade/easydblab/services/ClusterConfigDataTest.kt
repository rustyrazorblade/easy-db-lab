package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import org.assertj.core.api.Assertions.assertThat
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
        )

    @Test
    fun `publishes the tenant so every producer reads it from one place`() {
        val data = ClusterConfigData.of(control, state, "us-west-2")

        assertThat(data).containsEntry("tenant", "acme")
    }

    @Test
    fun `a cluster without a tenant publishes the default tenant`() {
        val data = ClusterConfigData.of(control, state.copy(initConfig = null), "us-west-2")

        assertThat(data).containsEntry("tenant", "default")
    }

    @Test
    fun `publishes the metrics and logs backend prefixes`() {
        val data = ClusterConfigData.of(control, state, "us-west-2")

        assertThat(data)
            .containsEntry("metrics_store_prefix", "mimir")
            .containsEntry("logs_store_prefix", "loki")
            .containsEntry("traces_store_prefix", "tempo")
            .containsEntry("cluster_name", "lab-abc123")
    }

    @Test
    fun `publishes the present capabilities in enum order, without a removed one`() {
        val recorded = setOf(NodeCapability.NODE_SSH, NodeCapability.S3, NodeCapability.HOST_PATH)

        val data = ClusterConfigData.of(control, state.copy(capabilities = recorded), "us-west-2")

        assertThat(data).containsEntry("capabilities", "s3,host-path,node-ssh")
    }

    @Test
    fun `the same recorded set always publishes the same value`() {
        val first =
            ClusterConfigData.of(
                control,
                state.copy(capabilities = linkedSetOf(NodeCapability.HOST_PATH, NodeCapability.S3)),
                "us-west-2",
            )
        val second =
            ClusterConfigData.of(
                control,
                state.copy(capabilities = linkedSetOf(NodeCapability.S3, NodeCapability.HOST_PATH)),
                "us-west-2",
            )

        assertThat(first["capabilities"]).isEqualTo(second["capabilities"])
    }

    @Test
    fun `a state with no recorded set publishes every capability`() {
        val data = ClusterConfigData.of(control, state, "us-west-2")

        assertThat(data).containsEntry("capabilities", NodeCapability.entries.joinToString(",") { it.serialName })
    }

    @Test
    fun `publishes the store in neutral keys, with the S3 endpoint of the cluster's region`() {
        val data = ClusterConfigData.of(control, state, "us-west-2")

        assertThat(data)
            .containsEntry("store_bucket", "acct-bucket")
            .containsEntry("store_prefix", "")
            .containsEntry("store_region", "us-east-1")
            .containsEntry("store_endpoint", "s3.us-east-1.amazonaws.com")
            .doesNotContainKeys("aws_region", "s3_bucket", "metrics_s3_prefix", "logs_s3_prefix", "traces_s3_prefix")
    }
}
