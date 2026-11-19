package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.access.DirectServiceAccess
import com.rustyrazorblade.easydblab.services.access.ServiceAccess
import com.rustyrazorblade.easydblab.services.access.ServiceEndpoint
import com.rustyrazorblade.easydblab.services.access.ServiceTarget
import com.rustyrazorblade.easydblab.services.access.SocksServiceAccess
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
            hosts = mutableMapOf(ServerType.Cassandra to listOf(dbHost)),
        )

    @BeforeEach
    fun setup() {
        resolver = DefaultKitEndpointResolver(DirectServiceAccess { clusterState })
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
    fun `an unparseable target kit yaml fails, naming the kit, the file and the parse problem`() {
        val kitDir = File(workspaceDir, "broken").also { it.mkdirs() }
        File(kitDir, "kit.yaml").writeText("this: is: not: valid: yaml: {{{{")

        assertThatThrownBy { resolver.resolveTargetVars(kitDir, clusterState) }
            .isInstanceOfSatisfying(TargetKitConfigException::class.java) { e ->
                assertThat(e.target).isEqualTo("broken")
                assertThat(e.file).isEqualTo(File(kitDir, "kit.yaml"))
                assertThat(e.reason).isNotBlank()
                assertThat(e.message).contains("broken").contains(File(kitDir, "kit.yaml").path).contains(e.reason)
            }
    }

    @Test
    fun `an HTTP endpoint whose node type has no node and no Service fails, naming the endpoint and the role`() {
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
        // clusterState has no app/stress nodes, and the kit declares no Service

        assertThatThrownBy { resolver.resolveTargetVars(kitDir, clusterState) }
            .isInstanceOfSatisfying(TargetEndpointUnreachableException::class.java) { e ->
                assertThat(e.target).isEqualTo("appkit")
                assertThat(e.endpoint).isEqualTo("HTTP")
                assertThat(e.reason).contains("No app node in cluster state")
            }
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

        val vars = DefaultKitEndpointResolver(DirectServiceAccess { stateWithApp }).resolveTargetVars(kitDir, stateWithApp)

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

/**
 * A client kit's pods run in the cluster, so a target published on a Service is reached by its
 * Service DNS name and Service port, whatever hosts `state.json` records (none on a cluster the
 * user brings).
 */
class KitEndpointResolverServiceDnsTest {
    @TempDir
    lateinit var workspaceDir: File

    private val noHosts = ClusterState(name = "byo")

    private fun installedKit(): File =
        File(workspaceDir, "postgres").apply {
            mkdirs()
            File(this, "kit.yaml").writeText(
                """
                name: postgres
                capabilities:
                  - type: sql
                    user: postgres
                endpoints:
                  - name: PostgreSQL
                    node-type: db
                    port: 30432
                    type: postgresql
                    database: postgres
                  - name: JDBC
                    node-type: db
                    port: 30432
                    type: jdbc
                    scheme: postgresql
                    path: /postgres
                """.trimIndent(),
            )
            File(this, "nodeport-service.yaml").writeText(
                """
                apiVersion: v1
                kind: Service
                metadata:
                  name: postgres-nodeport
                  namespace: default
                spec:
                  type: NodePort
                  ports:
                    - name: postgres
                      port: 5432
                      targetPort: 5432
                      nodePort: 30432
                """.trimIndent(),
            )
        }

    @Test
    fun `an endpoint published on a Service resolves to its DNS name and Service port with no recorded hosts`() {
        val vars = DefaultKitEndpointResolver(DirectServiceAccess { noHosts }).resolveTargetVars(installedKit(), noHosts)

        assertThat(vars["TARGET_PG_HOST"]).isEqualTo("postgres-nodeport.default.svc")
        assertThat(vars["TARGET_PG_PORT"]).isEqualTo("5432")
        assertThat(vars["TARGET_JDBC_URL"]).isEqualTo("jdbc:postgresql://postgres-nodeport.default.svc:5432/postgres")
    }

    private fun httpKit(): File =
        File(workspaceDir, "clickhouse").apply {
            mkdirs()
            File(this, "kit.yaml").writeText(
                "name: clickhouse\nendpoints:\n  - name: HTTP\n    node-type: db\n    port: 30123\n    type: http\n",
            )
            File(this, "nodeport-service.yaml").writeText(
                """
                apiVersion: v1
                kind: Service
                metadata:
                  name: clickhouse-nodeport
                  namespace: default
                spec:
                  type: NodePort
                  ports:
                    - name: http
                      port: 8123
                      nodePort: 30123
                """.trimIndent(),
            )
        }

    @Test
    fun `the HTTP endpoint, curled from the operator's machine, keeps a recorded node on the NodePort`() {
        val dbHost = ClusterHost("1.2.3.4", "10.0.1.5", "db0", "us-west-2a", "i-db0")
        val aws = noHosts.copy(hosts = mapOf(ServerType.Cassandra to listOf(dbHost)))

        val vars = DefaultKitEndpointResolver(DirectServiceAccess { aws }).resolveTargetVars(httpKit(), aws)

        assertThat(vars["TARGET_HTTP_URL"]).isEqualTo("http://10.0.1.5:30123")
    }

    @Test
    fun `the HTTP endpoint on a port-forward cluster is curled through a loopback forward to its Service`() {
        val opened = mutableListOf<ServiceTarget>()
        val forwarding =
            ServiceAccess { target ->
                opened += target
                ServiceEndpoint.Plain("127.0.0.1", 24123)
            }

        val vars = DefaultKitEndpointResolver(forwarding).resolveTargetVars(httpKit(), noHosts)

        assertThat(vars["TARGET_HTTP_URL"]).isEqualTo("http://127.0.0.1:24123")
        assertThat(opened.single()).isEqualTo(ServiceTarget("default", "clickhouse-nodeport", 8123, ServerType.Cassandra, 30123))
    }

    @Test
    fun `the HTTP endpoint never resolves to a Service DNS name this machine cannot look up`() {
        val nodeAccess = DefaultKitEndpointResolver(DirectServiceAccess { noHosts })

        assertThatThrownBy { nodeAccess.resolveTargetVars(httpKit(), noHosts) }
            .isInstanceOfSatisfying(TargetEndpointUnreachableException::class.java) { e ->
                assertThat(e.target).isEqualTo("clickhouse")
                assertThat(e.endpoint).isEqualTo("HTTP")
                assertThat(e.reason).isEqualTo(
                    "No db node in cluster state runs default/clickhouse-nodeport. Please ensure the environment is running.",
                )
            }
    }

    @Test
    fun `an HTTP endpoint reached through the SOCKS tunnel is the node address, which the curl wrapper routes`() {
        val dbHost = ClusterHost("1.2.3.4", "10.0.1.5", "db0", "us-west-2a", "i-db0")
        val aws = noHosts.copy(hosts = mapOf(ServerType.Cassandra to listOf(dbHost)))
        val socks = DefaultKitEndpointResolver(SocksServiceAccess({ aws }) { 21000 })

        assertThat(socks.resolveTargetVars(httpKit(), aws)["TARGET_HTTP_URL"]).isEqualTo("http://10.0.1.5:30123")
    }
}
