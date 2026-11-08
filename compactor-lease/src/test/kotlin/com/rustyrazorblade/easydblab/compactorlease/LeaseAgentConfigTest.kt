package com.rustyrazorblade.easydblab.compactorlease

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path

/** The agent's configuration, as the pod's environment gives it. */
class LeaseAgentConfigTest {
    private val env =
        mapOf(
            "S3_BUCKET" to "easy-db-lab-acct",
            "AWS_REGION" to "us-east-2",
            "CLUSTER_LABEL" to "lab-a-1234",
            "POD_NAME" to "account-compactor-7d9f",
            "LEASE_DIR" to "/lease",
            "SCRATCH_DIRS" to "/scratch/mimir:/scratch/loki:/scratch/tempo",
            "LEASE_TTL_SECONDS" to "120",
            "LEASE_RENEW_INTERVAL_SECONDS" to "15",
            "LEASE_RENEW_DEADLINE_SECONDS" to "60",
            "LEASE_STANDBY_POLL_SECONDS" to "15",
            "LEASE_GATE_POLL_SECONDS" to "1",
            "LEASE_GATE_GRACE_SECONDS" to "20",
        )

    @Test
    fun `the holder is the cluster label and the pod name`() {
        assertThat(LeaseAgentConfig.fromEnvironment(env).holder).isEqualTo("lab-a-1234/account-compactor-7d9f")
    }

    @Test
    fun `every scratch directory is read in order`() {
        assertThat(LeaseAgentConfig.fromEnvironment(env).scratchDirs)
            .containsExactly(Path.of("/scratch/mimir"), Path.of("/scratch/loki"), Path.of("/scratch/tempo"))
    }

    @Test
    fun `the bucket, region and lease directory come from the environment`() {
        val config = LeaseAgentConfig.fromEnvironment(env)

        assertThat(config.bucket).isEqualTo("easy-db-lab-acct")
        assertThat(config.region).isEqualTo("us-east-2")
        assertThat(config.leaseDir).isEqualTo(Path.of("/lease"))
    }

    @Test
    fun `no endpoint is set unless the environment names one`() {
        assertThat(LeaseAgentConfig.fromEnvironment(env).endpoint).isNull()
        assertThat(LeaseAgentConfig.fromEnvironment(env + ("S3_ENDPOINT" to "http://10.0.0.5:4566")).endpoint)
            .isEqualTo(URI("http://10.0.0.5:4566"))
    }

    @Test
    fun `a missing bucket is refused with its variable`() {
        assertThatThrownBy { LeaseAgentConfig.fromEnvironment(env - "S3_BUCKET") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("S3_BUCKET")
    }

    @Test
    fun `a blank pod name is refused with its variable`() {
        assertThatThrownBy { LeaseAgentConfig.fromEnvironment(env + ("POD_NAME" to "")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("POD_NAME")
    }
}
