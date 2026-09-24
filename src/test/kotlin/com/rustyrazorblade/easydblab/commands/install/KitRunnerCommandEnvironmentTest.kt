package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.test.get
import org.mockito.kotlin.times
import java.io.File

/**
 * The environment a kit phase runs in: TARGET_* variables from a kit-ref target, the layering
 * of runtime args over install-time args and cluster state, and the KUBECONFIG shell steps use.
 */
class KitRunnerCommandEnvironmentTest : KitRunnerCommandTestBase() {
    @Test
    fun `kit with kit-ref arg and valid target injects TARGET_JDBC_URL into script env`() {
        // Write target kit.yaml with JDBC endpoint
        File(File(workingDir, "clickhouse").also { it.mkdirs() }, "kit.yaml").writeText(
            """
            name: clickhouse
            capabilities:
              - type: sql
                user: default
                driver-class: com.clickhouse.jdbc.ClickHouseDriver
            endpoints:
              - name: JDBC
                node-type: db
                port: 8123
                type: jdbc
                scheme: clickhouse
                path: /default
            """.trimIndent(),
        )
        // Write bench kit.yaml with kit-ref arg
        writeKitYaml(
            "sysbench-clickhouse",
            """
            name: sysbench
            args:
              - flag: --target
                variable: TARGET
                type: kit-ref
            """.trimIndent(),
        )
        writeResolvedArgs("sysbench-clickhouse", mapOf("TARGET" to "clickhouse"))

        val outputFile = File(workingDir, "target_jdbc_url.txt")
        writeScript(
            "sysbench-clickhouse",
            "start",
            """echo "${'$'}TARGET_JDBC_URL" > "${outputFile.absolutePath}"""",
        )

        command("sysbench-clickhouse", "start").call()

        assertThat(outputFile.readText().trim()).isEqualTo("jdbc:clickhouse://10.0.2.1:8123/default")
    }

    @Test
    fun `kit with kit-ref arg and missing target dir produces no TARGET vars and no exception`() {
        writeKitYaml(
            "sysbench-missing",
            """
            name: sysbench
            args:
              - flag: --target
                variable: TARGET
                type: kit-ref
            """.trimIndent(),
        )
        writeResolvedArgs("sysbench-missing", mapOf("TARGET" to "doesnotexist"))

        val outputFile = File(workingDir, "target_url.txt")
        writeScript(
            "sysbench-missing",
            "start",
            """echo "${'$'}TARGET_JDBC_URL" > "${outputFile.absolutePath}"""",
        )

        val exitCode = command("sysbench-missing", "start").call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(outputFile.readText().trim()).isEmpty()
    }

    // -------------------------------------------------------------------------
    // runtime arg env layering (kit-command-args)
    // -------------------------------------------------------------------------

    @Test
    fun `runtime arg value overrides install-time resolved arg for same variable`() {
        writeResolvedArgs("mydb", mapOf("THROUGHPUT" to "100"))
        val outputFile = File(workingDir, "throughput.txt")
        writeScript("mydb", "start", """echo "${'$'}THROUGHPUT" > "${outputFile.absolutePath}"""")

        val cmd = command("mydb", "start")
        cmd.runtimeArgValues["THROUGHPUT"] = "50000"
        cmd.call()

        assertThat(outputFile.readText().trim()).isEqualTo("50000")
    }

    @Test
    fun `cluster state var is not shadowed by runtime arg with same variable name`() {
        val outputFile = File(workingDir, "cluster_name.txt")
        writeScript("mydb", "start", """echo "${'$'}CLUSTER_NAME" > "${outputFile.absolutePath}"""")

        val cmd = command("mydb", "start")
        cmd.runtimeArgValues["CLUSTER_NAME"] = "injected-by-kit"
        cmd.call()

        assertThat(outputFile.readText().trim()).isEqualTo("test-cluster")
    }

    @Test
    fun `kit without kit-ref arg does not inject TARGET vars`() {
        val outputFile = File(workingDir, "target_url.txt")
        writeScript(
            "mydb",
            "start",
            """echo "${'$'}TARGET_JDBC_URL" > "${outputFile.absolutePath}"""",
        )

        command("mydb", "start").call()

        assertThat(outputFile.readText().trim()).isEmpty()
    }

    private fun writeWorkspaceKubeconfig() {
        File(workingDir, Constants.K3s.LOCAL_KUBECONFIG).writeText(
            """
            apiVersion: v1
            kind: Config
            clusters:
              - name: default
                cluster:
                  server: https://10.0.0.1:6443
            contexts:
              - name: default
                context:
                  cluster: default
                  user: default
            current-context: default
            users:
              - name: default
                user:
                  token: abc123
            """.trimIndent() + "\n",
        )
    }

    @Test
    fun `shell step KUBECONFIG points at a proxied temp kubeconfig when a SOCKS port is published`() {
        writeWorkspaceKubeconfig()
        val kubeconfigCopy = File(workingDir, "kubeconfig-seen.txt")
        val kubeconfigPathFile = File(workingDir, "kubeconfig-path.txt")
        // Capture both the path kubectl would use and the content it would read, while the
        // temp kubeconfig still exists (it is deleted when the command finishes).
        writeScript(
            "mydb",
            "start",
            """
            echo "${'$'}KUBECONFIG" > "${kubeconfigPathFile.absolutePath}"
            cat "${'$'}KUBECONFIG" > "${kubeconfigCopy.absolutePath}"
            """.trimIndent(),
        )

        try {
            System.setProperty(Constants.Proxy.PORT_PROPERTY, "1080")
            command("mydb", "start").call()
        } finally {
            System.clearProperty(Constants.Proxy.PORT_PROPERTY)
        }

        // KUBECONFIG points at the resolver's temp copy, not the workspace kubeconfig.
        val kubeconfigPathUsed = kubeconfigPathFile.readText().trim()
        assertThat(File(kubeconfigPathUsed).name).startsWith("edl-kubeconfig-proxy-")
        // That temp kubeconfig routes kubectl/helm through the published SOCKS port.
        assertThat(kubeconfigCopy.readText()).contains("proxy-url").contains("socks5://127.0.0.1:1080")
        // The canonical workspace kubeconfig is never patched in place (fabric8 reads it).
        assertThat(File(workingDir, Constants.K3s.LOCAL_KUBECONFIG).readText()).doesNotContain("proxy-url")
    }

    @Test
    fun `shell step KUBECONFIG points at the workspace kubeconfig when no SOCKS port is published`() {
        writeWorkspaceKubeconfig()
        val kubeconfigPathFile = File(workingDir, "kubeconfig-path.txt")
        writeScript(
            "mydb",
            "start",
            """echo "${'$'}KUBECONFIG" > "${kubeconfigPathFile.absolutePath}"""",
        )

        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
        command("mydb", "start").call()

        assertThat(kubeconfigPathFile.readText().trim())
            .isEqualTo(File(workingDir, Constants.K3s.LOCAL_KUBECONFIG).absolutePath)
    }
}
