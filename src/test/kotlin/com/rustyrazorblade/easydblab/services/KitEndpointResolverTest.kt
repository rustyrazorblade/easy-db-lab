package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for DefaultKitEndpointResolver — resolves target kit endpoints into TARGET_* env vars.
 */
class KitEndpointResolverTest {
    @TempDir
    lateinit var workspaceDir: File

    private lateinit var resolver: KitEndpointResolver

    private val dbHost =
        ClusterHost(
            publicIp = "1.2.3.4",
            privateIp = "10.0.1.5",
            alias = "db0",
            availabilityZone = "us-west-2a",
            instanceId = "i-db0",
        )

    private val clusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            hosts = mutableMapOf(ServerType.Cassandra to listOf(dbHost)),
        )

    @BeforeEach
    fun setup() {
        resolver = DefaultKitEndpointResolver()
    }

    private fun writeKitYaml(
        kitName: String,
        yaml: String,
    ): File {
        val kitDir = File(workspaceDir, kitName).also { it.mkdirs() }
        File(kitDir, "kit.yaml").writeText(yaml)
        return kitDir
    }

    @Test
    fun `jdbc endpoint produces TARGET_JDBC vars`() {
        val kitDir =
            writeKitYaml(
                "clickhouse",
                """
                name: clickhouse
                capabilities:
                  - type: sql
                    user: default
                    driver-class: com.clickhouse.jdbc.ClickHouseDriver
                endpoints:
                  - name: JDBC
                    node-type: db
                    port: 30123
                    type: jdbc
                    scheme: clickhouse
                    path: /default?compress=0
                """.trimIndent(),
            )

        val vars = resolver.resolveTargetVars(kitDir, clusterState)

        assertThat(vars["TARGET_JDBC_URL"]).isEqualTo("jdbc:clickhouse://10.0.1.5:30123/default?compress=0")
        assertThat(vars["TARGET_JDBC_USER"]).isEqualTo("default")
        assertThat(vars["TARGET_JDBC_DRIVER"]).isEqualTo("com.clickhouse.jdbc.ClickHouseDriver")
    }

    @Test
    fun `postgresql endpoint produces TARGET_PG vars`() {
        val kitDir =
            writeKitYaml(
                "mydb",
                """
                name: mydb
                capabilities:
                  - type: sql
                    user: bench
                endpoints:
                  - name: PostgreSQL wire
                    node-type: db
                    port: 5432
                    type: postgresql
                    database: benchdb
                """.trimIndent(),
            )

        val vars = resolver.resolveTargetVars(kitDir, clusterState)

        assertThat(vars["TARGET_PG_HOST"]).isEqualTo("10.0.1.5")
        assertThat(vars["TARGET_PG_PORT"]).isEqualTo("5432")
        assertThat(vars["TARGET_PG_USER"]).isEqualTo("bench")
        assertThat(vars["TARGET_PG_DATABASE"]).isEqualTo("benchdb")
    }

    @Test
    fun `mysql endpoint produces TARGET_MYSQL vars`() {
        val kitDir =
            writeKitYaml(
                "tidb",
                """
                name: tidb
                capabilities:
                  - type: sql
                    user: root
                endpoints:
                  - name: MySQL wire
                    node-type: db
                    port: 4000
                    type: mysql
                    database: test
                """.trimIndent(),
            )

        val vars = resolver.resolveTargetVars(kitDir, clusterState)

        assertThat(vars["TARGET_MYSQL_HOST"]).isEqualTo("10.0.1.5")
        assertThat(vars["TARGET_MYSQL_PORT"]).isEqualTo("4000")
        assertThat(vars["TARGET_MYSQL_USER"]).isEqualTo("root")
        assertThat(vars["TARGET_MYSQL_DATABASE"]).isEqualTo("test")
    }

    @Test
    fun `multiple endpoint types all injected`() {
        val kitDir =
            writeKitYaml(
                "multidb",
                """
                name: multidb
                capabilities:
                  - type: sql
                    user: admin
                endpoints:
                  - name: JDBC
                    node-type: db
                    port: 5432
                    type: jdbc
                    scheme: postgresql
                  - name: PG wire
                    node-type: db
                    port: 5432
                    type: postgresql
                    database: mydb
                """.trimIndent(),
            )

        val vars = resolver.resolveTargetVars(kitDir, clusterState)

        assertThat(vars).containsKey("TARGET_JDBC_URL")
        assertThat(vars).containsKey("TARGET_PG_HOST")
    }

    @Test
    fun `missing target kit directory returns empty map`() {
        val missingDir = File(workspaceDir, "doesnotexist")

        val vars = resolver.resolveTargetVars(missingDir, clusterState)

        assertThat(vars).isEmpty()
    }

    @Test
    fun `unparseable kit yaml returns empty map`() {
        val kitDir = File(workspaceDir, "broken").also { it.mkdirs() }
        File(kitDir, "kit.yaml").writeText("this: is: not: valid: yaml: {{{{")

        val vars = resolver.resolveTargetVars(kitDir, clusterState)

        assertThat(vars).isEmpty()
    }

    @Test
    fun `endpoint whose node type has no nodes in cluster state is skipped`() {
        val kitDir =
            writeKitYaml(
                "appkit",
                """
                name: appkit
                endpoints:
                  - name: HTTP
                    node-type: app
                    port: 8080
                    type: http
                """.trimIndent(),
            )
        // clusterState has no app/stress nodes

        val vars = resolver.resolveTargetVars(kitDir, clusterState)

        assertThat(vars).doesNotContainKey("TARGET_HTTP_URL")
    }

    @Test
    fun `http endpoint produces TARGET_HTTP_URL`() {
        val appHost =
            ClusterHost(
                publicIp = "2.3.4.5",
                privateIp = "10.0.2.5",
                alias = "app0",
                availabilityZone = "us-west-2a",
                instanceId = "i-app0",
            )
        val stateWithApp =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = mutableMapOf(ServerType.Stress to listOf(appHost)),
            )
        val kitDir =
            writeKitYaml(
                "presto",
                """
                name: presto
                endpoints:
                  - name: HTTP
                    node-type: app
                    port: 8080
                    type: http
                """.trimIndent(),
            )

        val vars = resolver.resolveTargetVars(kitDir, stateWithApp)

        assertThat(vars["TARGET_HTTP_URL"]).isEqualTo("http://10.0.2.5:8080")
    }

    @Test
    fun `no sql capability produces empty user and driver`() {
        val kitDir =
            writeKitYaml(
                "noauth",
                """
                name: noauth
                endpoints:
                  - name: JDBC
                    node-type: db
                    port: 5432
                    type: jdbc
                    scheme: postgresql
                """.trimIndent(),
            )

        val vars = resolver.resolveTargetVars(kitDir, clusterState)

        assertThat(vars["TARGET_JDBC_USER"]).isEmpty()
        assertThat(vars["TARGET_JDBC_DRIVER"]).isEmpty()
    }
}
