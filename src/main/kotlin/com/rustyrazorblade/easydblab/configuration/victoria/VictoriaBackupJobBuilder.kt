package com.rustyrazorblade.easydblab.configuration.victoria

import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder

/**
 * Builds Fabric8 Job objects for VictoriaMetrics and VictoriaLogs backups.
 *
 * VictoriaMetrics backups use the native vmbackup tool.
 * VictoriaLogs backups use aws-cli to snapshot and sync partitions to S3.
 */
class VictoriaBackupJobBuilder {
    companion object {
        private const val NAMESPACE = "default"
        private const val TTL_SECONDS_AFTER_FINISHED = 300

        private const val VM_IMAGE = "victoriametrics/vmbackup:latest"
        private const val VM_DATA_PATH = "/mnt/db1/victoriametrics"
        private const val VM_LABEL = "victoriametrics-backup"

        private const val VL_IMAGE = "amazon/aws-cli:latest"
        private const val VL_DATA_PATH = "/mnt/db1/victorialogs"
        private const val VL_LABEL = "victorialogs-backup"
    }

    /**
     * Builds a VictoriaMetrics backup Job.
     *
     * Uses the vmbackup tool to create a native backup directly to S3.
     */
    fun buildMetricsBackupJob(
        jobName: String,
        s3Bucket: String,
        s3Key: String,
        awsRegion: String,
    ): Job =
        JobBuilder()
            .withNewMetadata()
            .withName(jobName)
            .withNamespace(NAMESPACE)
            .withLabels<String, String>(mapOf("app.kubernetes.io/name" to VM_LABEL))
            .endMetadata()
            .withNewSpec()
            .withTtlSecondsAfterFinished(TTL_SECONDS_AFTER_FINISHED)
            .withNewTemplate()
            .withNewSpec()
            .withRestartPolicy("Never")
            .withHostNetwork(true)
            .withDnsPolicy("ClusterFirstWithHostNet")
            .withNodeSelector<String, String>(
                mapOf("node-role.kubernetes.io/control-plane" to "true"),
            ).withTolerations(
                io.fabric8.kubernetes.api.model
                    .TolerationBuilder()
                    .withKey("node-role.kubernetes.io/control-plane")
                    .withOperator("Exists")
                    .withEffect("NoSchedule")
                    .build(),
            ).withContainers(
                ContainerBuilder()
                    .withName("vmbackup")
                    .withImage(VM_IMAGE)
                    .withEnv(
                        EnvVarBuilder()
                            .withName("AWS_REGION")
                            .withValue(awsRegion)
                            .build(),
                    ).withArgs(
                        "-storageDataPath=$VM_DATA_PATH",
                        "-snapshot.createURL=http://localhost:8428/snapshot/create",
                        "-dst=s3://$s3Bucket/$s3Key",
                        "-customS3Endpoint=https://s3.$awsRegion.amazonaws.com",
                    ).withVolumeMounts(
                        VolumeMountBuilder()
                            .withName("data")
                            .withMountPath(VM_DATA_PATH)
                            .withReadOnly(true)
                            .build(),
                    ).build(),
            ).withVolumes(
                io.fabric8.kubernetes.api.model
                    .VolumeBuilder()
                    .withName("data")
                    .withHostPath(
                        HostPathVolumeSourceBuilder()
                            .withPath(VM_DATA_PATH)
                            .withType("Directory")
                            .build(),
                    ).build(),
            ).endSpec()
            .endTemplate()
            .endSpec()
            .build()

    /**
     * Builds a VictoriaLogs backup Job.
     *
     * Uses aws-cli to create snapshots via the VictoriaLogs API,
     * sync them to S3, then clean up the snapshots.
     */
    fun buildLogsBackupJob(
        jobName: String,
        s3Bucket: String,
        s3Key: String,
        awsRegion: String,
    ): Job =
        JobBuilder()
            .withNewMetadata()
            .withName(jobName)
            .withNamespace(NAMESPACE)
            .withLabels<String, String>(mapOf("app.kubernetes.io/name" to VL_LABEL))
            .endMetadata()
            .withNewSpec()
            .withTtlSecondsAfterFinished(TTL_SECONDS_AFTER_FINISHED)
            .withNewTemplate()
            .withNewSpec()
            .withRestartPolicy("Never")
            .withHostNetwork(true)
            .withDnsPolicy("ClusterFirstWithHostNet")
            .withNodeSelector<String, String>(
                mapOf("node-role.kubernetes.io/control-plane" to "true"),
            ).withTolerations(
                io.fabric8.kubernetes.api.model
                    .TolerationBuilder()
                    .withKey("node-role.kubernetes.io/control-plane")
                    .withOperator("Exists")
                    .withEffect("NoSchedule")
                    .build(),
            ).withContainers(
                ContainerBuilder()
                    .withName("aws-cli")
                    .withImage(VL_IMAGE)
                    .withCommand("sh", "-c", buildLogsBackupScript(s3Bucket, s3Key, awsRegion))
                    .withVolumeMounts(
                        VolumeMountBuilder()
                            .withName("data")
                            .withMountPath(VL_DATA_PATH)
                            .withReadOnly(true)
                            .build(),
                    ).build(),
            ).withVolumes(
                io.fabric8.kubernetes.api.model
                    .VolumeBuilder()
                    .withName("data")
                    .withHostPath(
                        HostPathVolumeSourceBuilder()
                            .withPath(VL_DATA_PATH)
                            .withType("Directory")
                            .build(),
                    ).build(),
            ).endSpec()
            .endTemplate()
            .endSpec()
            .build()

    @Suppress("MaxLineLength")
    private fun buildLogsBackupScript(
        s3Bucket: String,
        s3Key: String,
        awsRegion: String,
    ): String =
        """
        |set -e
        |VL_URL="http://localhost:9428"
        |SYNC_FAILED=0
        |
        |echo "Creating VictoriaLogs snapshots..."
        |SNAPSHOT_JSON=${'$'}(curl -sf "${'$'}VL_URL/internal/partition/snapshot/create")
        |echo "Snapshots: ${'$'}SNAPSHOT_JSON"
        |
        |SNAPSHOT_PATHS=${'$'}(echo "${'$'}SNAPSHOT_JSON" | tr -d '[]"' | tr ',' '\n')
        |
        |if [ -z "${'$'}SNAPSHOT_PATHS" ]; then
        |  echo "No snapshots created. Nothing to back up."
        |  exit 0
        |fi
        |
        |for SNAP_PATH in ${'$'}SNAPSHOT_PATHS; do
        |  HOST_PATH=${'$'}(echo "${'$'}SNAP_PATH" | sed "s|/victoria-logs-data|/mnt/db1/victorialogs|")
        |  REL_PATH=${'$'}(echo "${'$'}SNAP_PATH" | sed "s|/victoria-logs-data/||")
        |  echo "Syncing ${'$'}HOST_PATH to s3://$s3Bucket/$s3Key/${'$'}REL_PATH ..."
        |  aws s3 sync "${'$'}HOST_PATH" "s3://$s3Bucket/$s3Key/${'$'}REL_PATH" --region $awsRegion || SYNC_FAILED=1
        |done
        |
        |for SNAP_PATH in ${'$'}SNAPSHOT_PATHS; do
        |  echo "Deleting snapshot: ${'$'}SNAP_PATH"
        |  curl -sf "${'$'}VL_URL/internal/partition/snapshot/delete?path=${'$'}SNAP_PATH" || \
        |    echo "WARNING: Failed to delete snapshot ${'$'}SNAP_PATH"
        |done
        |
        |if [ "${'$'}SYNC_FAILED" -eq 1 ]; then
        |  echo "ERROR: One or more S3 sync operations failed"
        |  exit 1
        |fi
        """.trimMargin()
}
