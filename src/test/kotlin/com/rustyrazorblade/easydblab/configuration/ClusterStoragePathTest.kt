package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ClusterStoragePathTest {
    @Test
    fun `from creates path with cluster prefix under account bucket`() {
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "easy-db-lab-account-bucket",
                clusterId = "abc12345-6789-0000-0000-000000000000",
            )

        val path = ClusterStoragePath.from(state)

        assertThat(path.toString()).isEqualTo("easy-db-lab-account-bucket/clusters/test-cluster-abc12345-6789-0000-0000-000000000000")
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

        val path = ClusterStoragePath.from(state)

        assertThat(path.spark().toString())
            .isEqualTo("easy-db-lab-account-bucket/clusters/myproject-def456/spark")
        assertThat(path.cassandra().toString())
            .isEqualTo("easy-db-lab-account-bucket/clusters/myproject-def456/cassandra")
        assertThat(path.config().toString())
            .isEqualTo("easy-db-lab-account-bucket/clusters/myproject-def456/config")
    }

    @Test
    fun `from throws when s3Bucket not configured`() {
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = null,
            )

        assertThatThrownBy { ClusterStoragePath.from(state) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("S3 bucket not configured")
    }

    @Test
    fun `resolve appends path segments`() {
        val path = ClusterStoragePath.root("my-bucket")
        val resolved = path.resolve("spark").resolve("app.jar")

        assertThat(resolved.toString())
            .isEqualTo("my-bucket/spark/app.jar")
        assertThat(resolved.getKey())
            .isEqualTo("spark/app.jar")
    }

    @Test
    fun `resolve handles paths with slashes`() {
        val path = ClusterStoragePath.root("my-bucket")
        val resolved = path.resolve("spark/nested/app.jar")

        assertThat(resolved.toString())
            .isEqualTo("my-bucket/spark/nested/app.jar")
    }

    @Test
    fun `resolve ignores empty segments`() {
        val path = ClusterStoragePath.root("my-bucket")
        val resolved = path.resolve("spark//app.jar")

        assertThat(resolved.toString())
            .isEqualTo("my-bucket/spark/app.jar")
    }

    @Test
    fun `getParent returns parent path`() {
        val path =
            ClusterStoragePath
                .root("my-bucket")
                .resolve("spark")
                .resolve("app.jar")

        val parent = path.getParent()

        assertThat(parent).isNotNull
        assertThat(parent.toString())
            .isEqualTo("my-bucket/spark")
    }

    @Test
    fun `getParent returns null for root`() {
        val path = ClusterStoragePath.root("my-bucket")

        assertThat(path.getParent()).isNull()
    }

    @Test
    fun `getFileName returns last segment`() {
        val path =
            ClusterStoragePath
                .root("my-bucket")
                .resolve("spark")
                .resolve("app.jar")

        assertThat(path.getFileName()).isEqualTo("app.jar")
    }

    @Test
    fun `getFileName returns null for root`() {
        val path = ClusterStoragePath.root("my-bucket")

        assertThat(path.getFileName()).isNull()
    }

    @Test
    fun `toString is bucket and key with no URI scheme`() {
        val path =
            ClusterStoragePath
                .root("my-bucket")
                .resolve("backups")
                .resolve("snapshot.tar.gz")

        assertThat(path.toString())
            .isEqualTo("my-bucket/backups/snapshot.tar.gz")
            .doesNotContain("://")
    }

    @Test
    fun `an empty store prefix gives the unprefixed key`() {
        val path = ClusterStoragePath("my-bucket", prefix = "").resolve("mimir")

        assertThat(path.getKey()).isEqualTo("mimir")
    }

    @Test
    fun `a store prefix leads every key`() {
        val root = ClusterStoragePath.root("my-bucket", prefix = "team-a")

        assertThat(root.getKey()).isEqualTo("team-a")
        assertThat(root.resolve("reports/acme").getKey()).isEqualTo("team-a/reports/acme")
        assertThat(root.resolve("reports/acme").toString()).isEqualTo("my-bucket/team-a/reports/acme")
    }

    @Test
    fun `a store prefix survives resolve, getParent and helpers but is never a file name`() {
        val root = ClusterStoragePath.root("my-bucket", prefix = "team-a")

        assertThat(
            root
                .spark()
                .resolve("app.jar")
                .getParent()
                ?.getKey(),
        ).isEqualTo("team-a/spark")
        assertThat(root.getFileName()).isNull()
        assertThat(root.getParent()).isNull()
    }

    @Test
    fun `from puts a cluster's files under its store prefix`() {
        val state =
            ClusterState(
                name = "lab",
                versions = mutableMapOf(),
                s3Bucket = "acct",
                clusterId = "abc",
            )

        assertThat(ClusterStoragePath.from(state, storePrefix = "team-a").getKey()).isEqualTo("team-a/clusters/lab-abc")
        assertThat(ClusterStoragePath.from(state).getKey()).isEqualTo("clusters/lab-abc")
    }

    @Test
    fun `a nested store prefix is split like any other path`() {
        val root = ClusterStoragePath.root("my-bucket", prefix = "/org/team-a/")

        assertThat(root.resolve("loki").getKey()).isEqualTo("org/team-a/loki")
    }

    @Test
    fun `root creates path without path segments`() {
        val path = ClusterStoragePath.root("my-bucket")

        assertThat(path.toString()).isEqualTo("my-bucket")
        assertThat(path.getKey()).isEmpty()
    }

    @Test
    fun `fromKey reconstructs path from S3 key`() {
        val path = ClusterStoragePath.fromKey("my-bucket", "spark/myapp.jar")

        assertThat(path.toString()).isEqualTo("my-bucket/spark/myapp.jar")
        assertThat(path.getKey()).isEqualTo("spark/myapp.jar")
        assertThat(path.getFileName()).isEqualTo("myapp.jar")
    }

    @Test
    fun `spark returns correct path`() {
        val path = ClusterStoragePath.root("my-bucket")
        val sparkPath = path.spark()

        assertThat(sparkPath.toString())
            .isEqualTo("my-bucket/spark")
    }

    @Test
    fun `cassandra returns correct path`() {
        val path = ClusterStoragePath.root("my-bucket")
        val cassandraPath = path.cassandra()

        assertThat(cassandraPath.toString())
            .isEqualTo("my-bucket/cassandra")
    }

    @Test
    fun `clickhouse returns correct path`() {
        val path = ClusterStoragePath.root("my-bucket")
        val clickhousePath = path.clickhouse()

        assertThat(clickhousePath.toString())
            .isEqualTo("my-bucket/clickhouse")
    }

    @Test
    fun `emrLogs returns correct path`() {
        val path = ClusterStoragePath.root("my-bucket")
        val emrLogsPath = path.emrLogs()

        assertThat(emrLogsPath.toString())
            .isEqualTo("my-bucket/spark/emr-logs")
    }

    @Test
    fun `backups returns correct path`() {
        val path = ClusterStoragePath.root("my-bucket")
        val backupsPath = path.backups()

        assertThat(backupsPath.toString())
            .isEqualTo("my-bucket/backups")
    }

    @Test
    fun `logs returns correct path`() {
        val path = ClusterStoragePath.root("my-bucket")
        val logsPath = path.logs()

        assertThat(logsPath.toString())
            .isEqualTo("my-bucket/logs")
    }

    @Test
    fun `data returns correct path`() {
        val path = ClusterStoragePath.root("my-bucket")
        val dataPath = path.data()

        assertThat(dataPath.toString())
            .isEqualTo("my-bucket/data")
    }

    @Test
    fun `convenience methods can be chained with resolve`() {
        val path = ClusterStoragePath.root("my-bucket")
        val jarPath = path.spark().resolve("myapp.jar")

        assertThat(jarPath.toString())
            .isEqualTo("my-bucket/spark/myapp.jar")
        assertThat(jarPath.getFileName()).isEqualTo("myapp.jar")
    }

    @Test
    fun `immutability - resolve returns new instance`() {
        val original = ClusterStoragePath.root("my-bucket")
        val resolved = original.resolve("subdir")

        assertThat(original.toString()).isEqualTo("my-bucket")
        assertThat(resolved.toString()).isEqualTo("my-bucket/subdir")
    }

    @Test
    fun `kubeconfig returns correct path`() {
        val path = ClusterStoragePath.root("my-bucket")
        val kubeconfigPath = path.kubeconfig()

        assertThat(kubeconfigPath.toString())
            .isEqualTo("my-bucket/config/kubeconfig")
    }

    @Test
    fun `k8s returns correct path`() {
        val path = ClusterStoragePath.root("my-bucket")
        val k8sPath = path.k8s()

        assertThat(k8sPath.toString())
            .isEqualTo("my-bucket/config/k8s")
    }

    @Test
    fun `config returns correct path`() {
        val path = ClusterStoragePath.root("my-bucket")
        val configPath = path.config()

        assertThat(configPath.toString())
            .isEqualTo("my-bucket/config")
    }

    @Test
    fun `cassandraPatch returns correct path`() {
        val path = ClusterStoragePath.root("my-bucket")
        val cassandraPatchPath = path.cassandraPatch()

        assertThat(cassandraPatchPath.toString())
            .isEqualTo("my-bucket/config/cassandra.patch.yaml")
    }

    @Test
    fun `cassandraVersions returns correct path`() {
        val path = ClusterStoragePath.root("my-bucket")
        val cassandraVersionsPath = path.cassandraVersions()

        assertThat(cassandraVersionsPath.toString())
            .isEqualTo("my-bucket/config/cassandra_versions.yaml")
    }

    @Test
    fun `envScript returns correct path`() {
        val path = ClusterStoragePath.root("my-bucket")
        val envScriptPath = path.envScript()

        assertThat(envScriptPath.toString())
            .isEqualTo("my-bucket/config/env.sh")
    }

    @Test
    fun `environmentScript returns correct path`() {
        val path = ClusterStoragePath.root("my-bucket")
        val environmentScriptPath = path.environmentScript()

        assertThat(environmentScriptPath.toString())
            .isEqualTo("my-bucket/config/environment.sh")
    }

    @Test
    fun `setupInstanceScript returns correct path`() {
        val path = ClusterStoragePath.root("my-bucket")
        val setupInstanceScriptPath = path.setupInstanceScript()

        assertThat(setupInstanceScriptPath.toString())
            .isEqualTo("my-bucket/config/setup_instance.sh")
    }

    @Test
    fun `clickhouseBackupsRoot produces account-level path not under clusters prefix`() {
        val path = ClusterStoragePath.clickhouseBackupsRoot("easy-db-lab-account-bucket")

        assertThat(path.toString()).isEqualTo("easy-db-lab-account-bucket/clickhouse-backups")
        assertThat(path.toString()).doesNotContain("clusters/")
        assertThat(path.bucket).isEqualTo("easy-db-lab-account-bucket")
        assertThat(path.getKey()).isEqualTo("clickhouse-backups")
    }

    @Test
    fun `clickhouseBackupsRoot can be resolved to a named backup`() {
        val path = ClusterStoragePath.clickhouseBackupsRoot("my-bucket").resolve("my-backup-2024")

        assertThat(path.toString()).isEqualTo("my-bucket/clickhouse-backups/my-backup-2024")
        assertThat(path.getFileName()).isEqualTo("my-backup-2024")
    }
}
