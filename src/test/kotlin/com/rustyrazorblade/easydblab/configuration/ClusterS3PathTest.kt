package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ClusterS3PathTest {
    @Test
    fun `from creates path with cluster prefix under account bucket`() {
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "easy-db-lab-account-bucket",
                clusterId = "abc12345-6789-0000-0000-000000000000",
            )

        val path = ClusterS3Path.from(state)

        assertThat(path.toString()).isEqualTo("s3://easy-db-lab-account-bucket/clusters/test-cluster-abc12345-6789-0000-0000-000000000000")
        assertThat(path.bucket).isEqualTo("easy-db-lab-account-bucket")
        assertThat(path.getKey()).isEqualTo("clusters/test-cluster-abc12345-6789-0000-0000-000000000000")
    }

    @Test
    fun `from with cluster prefix resolves technology paths correctly`() {
        val state =
            ClusterState(
                name = "myproject",
                versions = mutableMapOf(),
                s3Bucket = "easy-db-lab-account-bucket",
                clusterId = "def456",
            )

        val path = ClusterS3Path.from(state)

        assertThat(path.spark().toString())
            .isEqualTo("s3://easy-db-lab-account-bucket/clusters/myproject-def456/spark")
        assertThat(path.cassandra().toString())
            .isEqualTo("s3://easy-db-lab-account-bucket/clusters/myproject-def456/cassandra")
        assertThat(path.config().toString())
            .isEqualTo("s3://easy-db-lab-account-bucket/clusters/myproject-def456/config")
    }

    @Test
    fun `from throws when s3Bucket not configured`() {
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = null,
            )

        assertThatThrownBy { ClusterS3Path.from(state) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("S3 bucket not configured")
    }

    @Test
    fun `resolve appends path segments`() {
        val path = ClusterS3Path.root("my-bucket")
        val resolved = path.resolve("spark").resolve("app.jar")

        assertThat(resolved.toString())
            .isEqualTo("s3://my-bucket/spark/app.jar")
        assertThat(resolved.getKey())
            .isEqualTo("spark/app.jar")
    }

    @Test
    fun `resolve handles paths with slashes`() {
        val path = ClusterS3Path.root("my-bucket")
        val resolved = path.resolve("spark/nested/app.jar")

        assertThat(resolved.toString())
            .isEqualTo("s3://my-bucket/spark/nested/app.jar")
    }

    @Test
    fun `resolve ignores empty segments`() {
        val path = ClusterS3Path.root("my-bucket")
        val resolved = path.resolve("spark//app.jar")

        assertThat(resolved.toString())
            .isEqualTo("s3://my-bucket/spark/app.jar")
    }

    @Test
    fun `getParent returns parent path`() {
        val path =
            ClusterS3Path
                .root("my-bucket")
                .resolve("spark")
                .resolve("app.jar")

        val parent = path.getParent()

        assertThat(parent).isNotNull
        assertThat(parent.toString())
            .isEqualTo("s3://my-bucket/spark")
    }

    @Test
    fun `getParent returns null for root`() {
        val path = ClusterS3Path.root("my-bucket")

        assertThat(path.getParent()).isNull()
    }

    @Test
    fun `getFileName returns last segment`() {
        val path =
            ClusterS3Path
                .root("my-bucket")
                .resolve("spark")
                .resolve("app.jar")

        assertThat(path.getFileName()).isEqualTo("app.jar")
    }

    @Test
    fun `getFileName returns null for root`() {
        val path = ClusterS3Path.root("my-bucket")

        assertThat(path.getFileName()).isNull()
    }

    @Test
    fun `toString returns full S3 URI`() {
        val path =
            ClusterS3Path
                .root("my-bucket")
                .resolve("backups")
                .resolve("snapshot.tar.gz")

        assertThat(path.toString())
            .isEqualTo("s3://my-bucket/backups/snapshot.tar.gz")
    }

    @Test
    fun `toUri returns same as toString`() {
        val path = ClusterS3Path.root("my-bucket").resolve("file.txt")

        assertThat(path.toUri()).isEqualTo(path.toString())
    }

    @Test
    fun `root creates path without path segments`() {
        val path = ClusterS3Path.root("my-bucket")

        assertThat(path.toString()).isEqualTo("s3://my-bucket")
        assertThat(path.getKey()).isEmpty()
    }

    @Test
    fun `fromKey reconstructs path from S3 key`() {
        val path = ClusterS3Path.fromKey("my-bucket", "spark/myapp.jar")

        assertThat(path.toString()).isEqualTo("s3://my-bucket/spark/myapp.jar")
        assertThat(path.getKey()).isEqualTo("spark/myapp.jar")
        assertThat(path.getFileName()).isEqualTo("myapp.jar")
    }

    @Test
    fun `spark returns correct path`() {
        val path = ClusterS3Path.root("my-bucket")
        val sparkPath = path.spark()

        assertThat(sparkPath.toString())
            .isEqualTo("s3://my-bucket/spark")
    }

    @Test
    fun `cassandra returns correct path`() {
        val path = ClusterS3Path.root("my-bucket")
        val cassandraPath = path.cassandra()

        assertThat(cassandraPath.toString())
            .isEqualTo("s3://my-bucket/cassandra")
    }

    @Test
    fun `clickhouse returns correct path`() {
        val path = ClusterS3Path.root("my-bucket")
        val clickhousePath = path.clickhouse()

        assertThat(clickhousePath.toString())
            .isEqualTo("s3://my-bucket/clickhouse")
    }

    @Test
    fun `emrLogs returns correct path`() {
        val path = ClusterS3Path.root("my-bucket")
        val emrLogsPath = path.emrLogs()

        assertThat(emrLogsPath.toString())
            .isEqualTo("s3://my-bucket/spark/emr-logs")
    }

    @Test
    fun `backups returns correct path`() {
        val path = ClusterS3Path.root("my-bucket")
        val backupsPath = path.backups()

        assertThat(backupsPath.toString())
            .isEqualTo("s3://my-bucket/backups")
    }

    @Test
    fun `logs returns correct path`() {
        val path = ClusterS3Path.root("my-bucket")
        val logsPath = path.logs()

        assertThat(logsPath.toString())
            .isEqualTo("s3://my-bucket/logs")
    }

    @Test
    fun `data returns correct path`() {
        val path = ClusterS3Path.root("my-bucket")
        val dataPath = path.data()

        assertThat(dataPath.toString())
            .isEqualTo("s3://my-bucket/data")
    }

    @Test
    fun `convenience methods can be chained with resolve`() {
        val path = ClusterS3Path.root("my-bucket")
        val jarPath = path.spark().resolve("myapp.jar")

        assertThat(jarPath.toString())
            .isEqualTo("s3://my-bucket/spark/myapp.jar")
        assertThat(jarPath.getFileName()).isEqualTo("myapp.jar")
    }

    @Test
    fun `immutability - resolve returns new instance`() {
        val original = ClusterS3Path.root("my-bucket")
        val resolved = original.resolve("subdir")

        assertThat(original.toString()).isEqualTo("s3://my-bucket")
        assertThat(resolved.toString()).isEqualTo("s3://my-bucket/subdir")
    }

    @Test
    fun `kubeconfig returns correct path`() {
        val path = ClusterS3Path.root("my-bucket")
        val kubeconfigPath = path.kubeconfig()

        assertThat(kubeconfigPath.toString())
            .isEqualTo("s3://my-bucket/config/kubeconfig")
    }

    @Test
    fun `k8s returns correct path`() {
        val path = ClusterS3Path.root("my-bucket")
        val k8sPath = path.k8s()

        assertThat(k8sPath.toString())
            .isEqualTo("s3://my-bucket/config/k8s")
    }

    @Test
    fun `config returns correct path`() {
        val path = ClusterS3Path.root("my-bucket")
        val configPath = path.config()

        assertThat(configPath.toString())
            .isEqualTo("s3://my-bucket/config")
    }

    @Test
    fun `cassandraPatch returns correct path`() {
        val path = ClusterS3Path.root("my-bucket")
        val cassandraPatchPath = path.cassandraPatch()

        assertThat(cassandraPatchPath.toString())
            .isEqualTo("s3://my-bucket/config/cassandra.patch.yaml")
    }

    @Test
    fun `cassandraConfig returns correct path`() {
        val path = ClusterS3Path.root("my-bucket")
        val cassandraConfigPath = path.cassandraConfig()

        assertThat(cassandraConfigPath.toString())
            .isEqualTo("s3://my-bucket/config/cassandra-config")
    }

    @Test
    fun `cassandraVersions returns correct path`() {
        val path = ClusterS3Path.root("my-bucket")
        val cassandraVersionsPath = path.cassandraVersions()

        assertThat(cassandraVersionsPath.toString())
            .isEqualTo("s3://my-bucket/config/cassandra_versions.yaml")
    }

    @Test
    fun `environmentScript returns correct path`() {
        val path = ClusterS3Path.root("my-bucket")
        val environmentScriptPath = path.environmentScript()

        assertThat(environmentScriptPath.toString())
            .isEqualTo("s3://my-bucket/config/environment.sh")
    }

    @Test
    fun `setupInstanceScript returns correct path`() {
        val path = ClusterS3Path.root("my-bucket")
        val setupInstanceScriptPath = path.setupInstanceScript()

        assertThat(setupInstanceScriptPath.toString())
            .isEqualTo("s3://my-bucket/config/setup_instance.sh")
    }

    @Test
    fun `victoriaMetrics returns correct path`() {
        val path = ClusterS3Path.root("my-bucket")
        val victoriaMetricsPath = path.victoriaMetrics()

        assertThat(victoriaMetricsPath.toString())
            .isEqualTo("s3://my-bucket/victoriametrics")
    }

    @Test
    fun `victoriaLogs returns correct path`() {
        val path = ClusterS3Path.root("my-bucket")
        val victoriaLogsPath = path.victoriaLogs()

        assertThat(victoriaLogsPath.toString())
            .isEqualTo("s3://my-bucket/victorialogs")
    }

    @Test
    fun `victoriaMetrics can be chained with resolve for timestamp`() {
        val path = ClusterS3Path.root("my-bucket")
        val backupPath = path.victoriaMetrics().resolve("20240101-120000")

        assertThat(backupPath.toString())
            .isEqualTo("s3://my-bucket/victoriametrics/20240101-120000")
    }

    @Test
    fun `victoriaLogs can be chained with resolve for timestamp`() {
        val path = ClusterS3Path.root("my-bucket")
        val backupPath = path.victoriaLogs().resolve("20240101-120000")

        assertThat(backupPath.toString())
            .isEqualTo("s3://my-bucket/victorialogs/20240101-120000")
    }
}
