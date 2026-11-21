package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.StepExecutionContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
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
    fun `a target kit whose one-pod endpoint no pod serves fails the phase before the script runs`() {
        File(File(workingDir, "trino").also { it.mkdirs() }, "kit.yaml").writeText(
            """
            name: trino
            endpoints:
              - name: UI
                node-type: db
                port: 8080
                type: http
                pod-selector: app=${'$'}{KIT_NAME}
            """.trimIndent(),
        )
        writeKitYaml(
            "loadtest",
            """
            name: loadtest
            args:
              - flag: --target
                variable: TARGET
                type: kit-ref
            """.trimIndent(),
        )
        writeResolvedArgs("loadtest", mapOf("TARGET" to "trino"))
        whenever(mockKubeService.listPodsByLabel(any(), any())).thenReturn(Result.success(emptyList()))
        val ran = File(workingDir, "ran.txt")
        writeScript("loadtest", "start", """echo ran > "${ran.absolutePath}"""")

        var exitCode = 0
        val events = captureEvents { exitCode = command("loadtest", "start").call() }

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(ran).doesNotExist()
        assertThat(events.filterIsInstance<Event.Kit.TargetNotServed>().single()).isEqualTo(
            Event.Kit.TargetNotServed(
                kit = "loadtest",
                phase = "start",
                targetKit = "trino",
                endpoint = "UI",
                reason = "no running, ready pod matches app=trino in namespace default",
            ),
        )
        verify(mockKubeService).listPodsByLabel("app=trino", "default")
    }

    // A bench kit is stopped and removed after its target, the usual order: stop and uninstall
    // tear down what the bench kit made, so they run without the vars of an endpoint no pod
    // serves, and every other phase still fails before its script runs.
    @ParameterizedTest(name = "{0}")
    @CsvSource("stop, true", "uninstall, true", "start, false", "backup, false")
    fun `only a teardown phase runs while its target serves no endpoint`(
        phase: String,
        runs: Boolean,
    ) {
        File(File(workingDir, "trino").also { it.mkdirs() }, "kit.yaml").writeText(
            """
            name: trino
            endpoints:
              - name: UI
                node-type: db
                port: 8080
                type: http
                pod-selector: app=${'$'}{KIT_NAME}
              - name: JDBC
                node-type: db
                port: 30080
                type: jdbc
                scheme: trino
            """.trimIndent(),
        )
        writeKitYaml(
            "loadtest",
            """
            name: loadtest
            args:
              - flag: --target
                variable: TARGET
                type: kit-ref
            """.trimIndent(),
        )
        writeResolvedArgs("loadtest", mapOf("TARGET" to "trino"))
        whenever(mockKubeService.listPodsByLabel(any(), any())).thenReturn(Result.success(emptyList()))
        val ran = File(workingDir, "ran.txt")
        writeScript(
            "loadtest",
            phase,
            """echo "jdbc=${'$'}TARGET_JDBC_URL http=${'$'}{TARGET_HTTP_URL:-unset}" > "${ran.absolutePath}"""",
        )

        var exitCode = 0
        val events = captureEvents { exitCode = command("loadtest", phase).call() }

        if (runs) {
            assertThat(exitCode).isEqualTo(0)
            assertThat(ran.readText().trim()).isEqualTo("jdbc=jdbc:trino://10.0.2.1:30080 http=unset")
            assertThat(events.filterIsInstance<Event.Kit.TargetNotServed>()).isEmpty()
        } else {
            assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
            assertThat(ran).doesNotExist()
            assertThat(events.filterIsInstance<Event.Kit.TargetNotServed>().single().phase).isEqualTo(phase)
        }
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
            echo "${'$'}${Constants.Kit.LIBRARY_ENV}" >> "${seen.absolutePath}"
            """.trimIndent(),
        )

        val exitCode = command("mydb", "start").call()

        assertThat(exitCode).isZero()
        val (kubeconfig, path, kubectl, library) = seen.readLines()
        assertThat(
            library,
        ).isEqualTo(File(workingDir, "${Constants.ToolWrappers.DIRECTORY}/${Constants.ToolWrappers.KIT_LIBRARY}").absolutePath)
        assertThat(kubeconfig).isEqualTo(File(workingDir, Constants.K3s.LOCAL_KUBECONFIG).absolutePath)
        assertThat(File(kubeconfig)).isFile()
        assertThat(path.split(File.pathSeparator).first()).isEqualTo(File(workingDir, Constants.ToolWrappers.DIRECTORY).absolutePath)
        assertThat(kubectl).isEqualTo(File(workingDir, "${Constants.ToolWrappers.DIRECTORY}/kubectl").absolutePath)
    }

    @Test
    fun `the workspace is the context working directory, not the kit directory's parent`() {
        val seen = File(workingDir, "seen.txt")
        writeScript("sources/mydb", "start", """echo "${'$'}KUBECONFIG" > "${seen.absolutePath}"""")

        val exitCode = KitRunnerCommand("mydb", File(workingDir, "sources/mydb"), "start").call()

        assertThat(exitCode).isZero()
        assertThat(seen.readText().trim()).isEqualTo(File(workingDir, Constants.K3s.LOCAL_KUBECONFIG).absolutePath)
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
