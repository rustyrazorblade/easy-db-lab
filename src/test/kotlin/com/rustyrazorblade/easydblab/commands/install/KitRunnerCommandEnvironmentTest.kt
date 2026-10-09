package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import com.rustyrazorblade.easydblab.services.StepExecutionContext
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import java.io.File

/**
 * The environment a kit phase runs in: TARGET_* variables from a kit-ref target, the layering
 * of runtime args over install-time args and cluster state, and the KUBECONFIG and PATH that
 * phase scripts and typed steps get from KitProcessEnvironment.
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

    @Test
    fun `a phase script gets the absolute workspace kubeconfig and the workspace bin first on PATH`() {
        val seen = File(workingDir, "seen.txt")
        writeScript(
            "mydb",
            "start",
            """
            echo "${'$'}KUBECONFIG" > "${seen.absolutePath}"
            echo "${'$'}PATH" >> "${seen.absolutePath}"
            command -v kubectl >> "${seen.absolutePath}"
            """.trimIndent(),
        )

        val exitCode = command("mydb", "start").call()

        assertThat(exitCode).isZero()
        val (kubeconfig, path, kubectl) = seen.readLines()
        assertThat(kubeconfig).isEqualTo(File(workingDir, Constants.K3s.LOCAL_KUBECONFIG).absolutePath)
        assertThat(File(kubeconfig)).isFile()
        assertThat(path.split(File.pathSeparator).first()).isEqualTo(File(workingDir, Constants.ToolWrappers.DIRECTORY).absolutePath)
        assertThat(kubectl).isEqualTo(File(workingDir, "${Constants.ToolWrappers.DIRECTORY}/kubectl").absolutePath)
    }

    @Test
    fun `a published SOCKS port does not make a temporary kubeconfig`() {
        val workspaceKubeconfig = File(workingDir, Constants.K3s.LOCAL_KUBECONFIG)
        val seen = File(workingDir, "seen.txt")
        writeScript("mydb", "start", """echo "${'$'}KUBECONFIG" > "${seen.absolutePath}"""")

        try {
            System.setProperty(Constants.Proxy.PORT_PROPERTY, "1080")
            command("mydb", "start").call()
        } finally {
            System.clearProperty(Constants.Proxy.PORT_PROPERTY)
        }

        assertThat(seen.readText().trim()).isEqualTo(workspaceKubeconfig.absolutePath)
        assertThat(workspaceKubeconfig).hasContent(WORKSPACE_KUBECONFIG)
    }

    @Test
    fun `a phase script does not start when the workspace kubeconfig is missing`() {
        File(workingDir, Constants.K3s.LOCAL_KUBECONFIG).delete()
        val ran = File(workingDir, "ran.txt")
        writeScript("mydb", "start", """touch "${ran.absolutePath}"""")

        assertThatThrownBy { command("mydb", "start").call() }
            .hasMessageContaining(File(workingDir, Constants.K3s.LOCAL_KUBECONFIG).absolutePath)

        assertThat(ran).doesNotExist()
    }

    @Test
    fun `typed phase steps get the workspace and the absolute kubeconfig`() {
        writeKitYaml(
            "mydb",
            """
            name: mydb
            start:
              - type: shell
                script: "true"
            """.trimIndent(),
        )

        command("mydb", "start").call()

        val captor = argumentCaptor<StepExecutionContext>()
        verify(mockWorkloadStepExecutor).execute(any(), any(), captor.capture())
        assertThat(captor.firstValue.workspaceDir.absoluteFile).isEqualTo(workingDir.absoluteFile)
        assertThat(captor.firstValue.variables["KUBECONFIG"]).isEqualTo(File(workingDir, Constants.K3s.LOCAL_KUBECONFIG).absolutePath)
    }
}
