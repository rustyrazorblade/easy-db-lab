package com.rustyrazorblade.easydblab.configuration.victoria

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for VictoriaBackupJobBuilder.
 *
 * Focuses on dynamic parameter injection and the no-resource-limits rule.
 * Static structure (images, hostNetwork, etc.) is verified by K8sServiceIntegrationTest.
 */
class VictoriaBackupJobBuilderTest {
    private val builder = VictoriaBackupJobBuilder()

    @Test
    fun `metrics backup job injects S3 destination and region into args`() {
        val job = builder.buildMetricsBackupJob("vmbackup-test", "my-bucket", "backups/vm", "eu-west-1")
        val container =
            job.spec.template.spec.containers
                .first()

        // Verify dynamic parameters are injected into vmbackup args
        assertThat(container.args).contains("-dst=s3://my-bucket/backups/vm")
        assertThat(container.args).contains("-customS3Endpoint=https://s3.eu-west-1.amazonaws.com")
        assertThat(container.env.first { it.name == "AWS_REGION" }.value).isEqualTo("eu-west-1")
    }

    @Test
    fun `logs backup script injects S3 destination and region`() {
        val job = builder.buildLogsBackupJob("vlbackup-test", "my-bucket", "backups/vl", "ap-southeast-1")
        val script =
            job.spec.template.spec.containers
                .first()
                .command
                .last()

        // Verify dynamic parameters appear in the shell script
        assertThat(script).contains("s3://my-bucket/backups/vl")
        assertThat(script).contains("--region ap-southeast-1")
    }

    @Test
    fun `no containers have resource limits or requests`() {
        val metricsJob = builder.buildMetricsBackupJob("vm-test", "b", "k", "us-west-2")
        val logsJob = builder.buildLogsBackupJob("vl-test", "b", "k", "us-west-2")

        for (job in listOf(metricsJob, logsJob)) {
            for (container in job.spec.template.spec.containers) {
                val res = container.resources
                if (res != null) {
                    assertThat(res.limits.orEmpty())
                        .withFailMessage("Job/${job.metadata.name} container '${container.name}' has resource limits")
                        .isEmpty()
                    assertThat(res.requests.orEmpty())
                        .withFailMessage("Job/${job.metadata.name} container '${container.name}' has resource requests")
                        .isEmpty()
                }
            }
        }
    }
}
