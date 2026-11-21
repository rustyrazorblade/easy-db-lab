package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

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
        resolver = DefaultKitEndpointResolver(KitEndpointHost { selector, _ -> error("unexpected pod query for $selector") })
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

    // #1224: a hostPort endpoint is served only on the node that runs its pod.

    private val appHosts =
        ClusterState(
            name = "test-cluster",
            hosts =
                mutableMapOf(
                    ServerType.Stress to
                        listOf(
                            dbHost.copy(alias = "app0", privateIp = "10.0.2.5", instanceId = "i-app0"),
                            dbHost.copy(alias = "app1", privateIp = "10.0.2.6", instanceId = "i-app1"),
                        ),
                ),
        )

    private fun prestoKit(): File =
        writeKitYaml(
            "presto",
            """
            name: presto
            runtime:
              type: helm
              namespace: analytics
            endpoints:
              - name: JDBC
                node-type: app
                port: 8080
                type: jdbc
                scheme: presto
                pod-selector: "app.kubernetes.io/name=${'$'}{KIT_NAME},app.kubernetes.io/component=coordinator"
            """.trimIndent(),
        )

    private fun coordinator(
        hostIp: String,
        ready: String = "1/1",
    ) = KubernetesPod(
        namespace = "analytics",
        name = "presto-coordinator-0",
        status = "Running",
        ready = ready,
        restarts = 0,
        age = Duration.ofMinutes(1),
        hostIp = hostIp,
    )

    @Test
    fun `a pod-selector endpoint targets the node that runs its pod, not the first node`() {
        val queries = mutableListOf<Pair<String, String>>()
        val podResolver =
            DefaultKitEndpointResolver(
                KitEndpointHost { selector, namespace ->
                    queries += selector to namespace
                    Result.success(listOf(coordinator("10.0.2.6")))
                },
            )

        val vars = podResolver.resolveTargetVars(prestoKit(), appHosts)

        assertThat(vars["TARGET_JDBC_URL"]).isEqualTo("jdbc:presto://10.0.2.6:8080")
        assertThat(queries)
            .containsExactly("app.kubernetes.io/name=presto,app.kubernetes.io/component=coordinator" to "analytics")
    }

    @Test
    fun `endpoints that share a pod selector are looked up once and target the same node`() {
        val kitDir =
            writeKitYaml(
                "presto",
                """
                name: presto
                endpoints:
                  - name: UI
                    node-type: app
                    port: 8080
                    type: http
                    pod-selector: "app=${'$'}{KIT_NAME}"
                  - name: JDBC
                    node-type: app
                    port: 8080
                    type: jdbc
                    scheme: presto
                    pod-selector: "app=${'$'}{KIT_NAME}"
                """.trimIndent(),
            )
        val answers = ArrayDeque(listOf(listOf(coordinator("10.0.2.6")), listOf(coordinator("10.0.2.5"))))
        var queries = 0
        val podResolver =
            DefaultKitEndpointResolver(
                KitEndpointHost { _, _ ->
                    queries++
                    Result.success(answers.removeFirst())
                },
            )

        val vars = podResolver.resolveTargetVars(kitDir, appHosts)

        assertThat(queries).isEqualTo(1)
        assertThat(vars["TARGET_HTTP_URL"]).isEqualTo("http://10.0.2.6:8080")
        assertThat(vars["TARGET_JDBC_URL"]).isEqualTo("jdbc:presto://10.0.2.6:8080")
    }

    @Test
    fun `a pod-selector endpoint with no serving pod gets no TARGET vars instead of the first node`() {
        val podResolver =
            DefaultKitEndpointResolver(KitEndpointHost { _, _ -> Result.success(listOf(coordinator("10.0.2.6", ready = "0/1"))) })

        val vars = podResolver.resolveTargetVars(prestoKit(), appHosts)

        assertThat(vars).doesNotContainKey("TARGET_JDBC_URL")
    }
}
