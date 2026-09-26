package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
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
            .containsEntry("metrics_s3_prefix", "observabilitymetrics")
            .containsEntry("logs_s3_prefix", "observability/logs")
            .containsEntry("traces_s3_prefix", "observability/traces")
            .containsEntry("cluster_name", "lab-abc123")
    }
}
