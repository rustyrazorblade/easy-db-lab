package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import com.rustyrazorblade.easydblab.services.HelmService
import com.rustyrazorblade.easydblab.services.KitHookExecutor
import com.rustyrazorblade.easydblab.services.MetricsRegistryService
import com.rustyrazorblade.easydblab.services.WorkloadStepExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class KitRunnerCommandFactoryTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val mockGrafanaDashboardService: GrafanaDashboardService = mock()
    private val mockWorkloadStepExecutor: WorkloadStepExecutor = mock()
    private val mockMetricsRegistryService: MetricsRegistryService = mock()
    private val mockHelmService: HelmService = mock()
    private val mockSocksProxyService: SocksProxyService = mock()
    private val mockKitHookExecutor: KitHookExecutor = mock()

    @TempDir
    lateinit var kitDir: File

    private val factory = KitRunnerCommandFactory()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single<GrafanaDashboardService> { mockGrafanaDashboardService }
                single<WorkloadStepExecutor> { mockWorkloadStepExecutor }
                single<MetricsRegistryService> { mockMetricsRegistryService }
                single<HelmService> { mockHelmService }
                single<SocksProxyService> { mockSocksProxyService }
                single<KitHookExecutor> { mockKitHookExecutor }
            },
        )

    @BeforeEach
    fun setup() {
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "test-bucket",
                initConfig = InitConfig(region = "us-west-2", name = "test-cluster"),
                hosts =
                    mapOf(
                        ServerType.Control to
                            listOf(
                                ClusterHost("54.1.2.3", "10.0.0.1", "control0", "us-west-2a", "i-ctrl"),
                            ),
                    ),
            )
        whenever(mockClusterStateManager.load()).thenReturn(state)
    }

    private fun writeKitYaml(yaml: String) {
        File(kitDir, Constants.Kit.CONFIG_FILE).writeText(yaml)
    }

    @Test
    fun `backup and restore registered as subcommands when declared in kit yaml`() {
        writeKitYaml(
            """
            name: clickhouse
            backup:
              - type: shell
                script: echo backup
            restore:
              - type: shell
                script: echo restore
            """.trimIndent(),
        )

        val groupCl = factory.buildKitGroup("clickhouse", kitDir)
        val subcommandNames = groupCl.subcommands.keys

        assertThat(subcommandNames).contains("backup", "restore")
    }

    @Test
    fun `backup and restore not registered when absent from kit yaml`() {
        writeKitYaml(
            """
            name: clickhouse
            start:
              - type: shell
                script: echo start
            stop:
              - type: shell
                script: echo stop
            """.trimIndent(),
        )

        val groupCl = factory.buildKitGroup("clickhouse", kitDir)
        val subcommandNames = groupCl.subcommands.keys

        assertThat(subcommandNames).doesNotContain("backup", "restore")
        assertThat(subcommandNames).contains("start", "stop")
    }

    @Test
    fun `sh scripts in bin dir are discovered as phases`() {
        val binDir = File(kitDir, "bin").also { it.mkdirs() }
        val execPerms =
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            )
        listOf("start.sh", "stop.sh").forEach { name ->
            val f = File(binDir, name).also { it.writeText("#!/bin/sh\necho ${name.removeSuffix(".sh")}") }
            java.nio.file.Files
                .setPosixFilePermissions(f.toPath(), execPerms)
        }

        val groupCl = factory.buildKitGroup("mydb", kitDir)
        val subcommandNames = groupCl.subcommands.keys

        assertThat(subcommandNames).contains("start", "stop")
    }

    @Test
    fun `executable scripts without sh suffix are discovered as phases`() {
        val binDir = File(kitDir, "bin").also { it.mkdirs() }
        val script = File(binDir, "run")
        script.writeText("#!/bin/sh\necho run")
        val perms =
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            )
        java.nio.file.Files
            .setPosixFilePermissions(script.toPath(), perms)

        val groupCl = factory.buildKitGroup("mydb", kitDir)

        assertThat(groupCl.subcommands.keys).contains("run")
    }

    @Test
    fun `group command call prints usage`() {
        writeKitYaml(
            """
            name: mydb
            start:
              - type: shell
                script: echo start
            """.trimIndent(),
        )

        val groupCl = factory.buildKitGroup("mydb", kitDir)
        val exitCode = groupCl.execute()

        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `install phase is not exposed as a runner subcommand`() {
        writeKitYaml(
            """
            name: mydb
            install:
              - type: shell
                script: echo install
            start:
              - type: shell
                script: echo start
            """.trimIndent(),
        )

        val groupCl = factory.buildKitGroup("mydb", kitDir)
        val subcommandNames = groupCl.subcommands.keys

        assertThat(subcommandNames).contains("start")
        assertThat(subcommandNames).doesNotContain("install")
    }

    @Test
    fun `unknown typed phase falls through to script path`() {
        writeKitYaml(
            """
            name: mydb
            start:
              - type: shell
                script: echo start
            """.trimIndent(),
        )

        val groupCl = factory.buildKitGroup("mydb", kitDir)
        assertThat(groupCl.subcommands.keys).doesNotContain("frobnicate")
    }

    @Test
    fun `status subcommand always present with no kit yaml`() {
        val groupCl = factory.buildKitGroup("mydb", kitDir)
        assertThat(groupCl.subcommands.keys).contains("status")
    }

    @Test
    fun `status subcommand always present with kit yaml`() {
        writeKitYaml(
            """
            name: presto
            start:
              - type: shell
                script: echo start
            stop:
              - type: shell
                script: echo stop
            """.trimIndent(),
        )

        val groupCl = factory.buildKitGroup("presto", kitDir)
        assertThat(groupCl.subcommands.keys).contains("status", "start", "stop")
    }

    @Test
    fun `status subcommand present alongside bin scripts`() {
        val binDir = File(kitDir, "bin").also { it.mkdirs() }
        val script = File(binDir, "start.sh")
        script.writeText("#!/bin/sh\necho start")
        val execPerms =
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            )
        java.nio.file.Files
            .setPosixFilePermissions(script.toPath(), execPerms)

        val groupCl = factory.buildKitGroup("mydb", kitDir)
        assertThat(groupCl.subcommands.keys).contains("status", "start")
    }

    @Test
    fun `status subcommand not overridden by script named status`() {
        val binDir = File(kitDir, "bin").also { it.mkdirs() }
        val script = File(binDir, "status.sh")
        script.writeText("#!/bin/sh\necho custom status")
        val execPerms =
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            )
        java.nio.file.Files
            .setPosixFilePermissions(script.toPath(), execPerms)

        val groupCl = factory.buildKitGroup("mydb", kitDir)
        assertThat(groupCl.subcommands.keys).contains("status")
        assertThat(groupCl.subcommands["status"]?.commandSpec?.userObject())
            .isInstanceOf(KitStatusCommand::class.java)
    }

    // -------------------------------------------------------------------------
    // Capability command registration
    // -------------------------------------------------------------------------

    @Test
    fun `sql capability with jdbc endpoint registers sql subcommand`() {
        writeKitYaml(
            """
            name: presto
            endpoints:
              - name: "JDBC"
                node-type: app
                port: 8080
                type: jdbc
                scheme: presto
                path: /cassandra
            capabilities:
              - type: sql
                user: easy-db-lab
                driver-class: com.facebook.presto.jdbc.PrestoDriver
            """.trimIndent(),
        )

        val groupCl = factory.buildKitGroup("presto", kitDir)
        assertThat(groupCl.subcommands.keys).contains("sql")
    }

    @Test
    fun `sql capability without jdbc endpoint does not register sql subcommand`() {
        writeKitYaml(
            """
            name: mydb
            endpoints:
              - name: "HTTP"
                node-type: app
                port: 8080
                type: http
            capabilities:
              - type: sql
                user: test
            """.trimIndent(),
        )

        val groupCl = factory.buildKitGroup("mydb", kitDir)
        assertThat(groupCl.subcommands.keys).doesNotContain("sql")
    }

    @Test
    fun `sql capability with jdbc endpoint and no driver-class registers sql subcommand`() {
        writeKitYaml(
            """
            name: postgres
            endpoints:
              - name: "JDBC"
                node-type: db
                port: 30432
                type: jdbc
                scheme: postgresql
                path: /postgres
            capabilities:
              - type: sql
                user: postgres
            """.trimIndent(),
        )

        val groupCl = factory.buildKitGroup("postgres", kitDir)
        assertThat(groupCl.subcommands.keys).contains("sql")
    }

    @Test
    fun `unknown capability type is ignored and does not throw`() {
        writeKitYaml(
            """
            name: mydb
            endpoints:
              - name: "JDBC"
                node-type: app
                port: 8080
                type: jdbc
                scheme: presto
            capabilities:
              - type: tpch-load
                user: test
            """.trimIndent(),
        )

        val groupCl = factory.buildKitGroup("mydb", kitDir)
        assertThat(groupCl.subcommands.keys).doesNotContain("tpch-load")
    }

    @Test
    fun `capability commands coexist with lifecycle phases`() {
        writeKitYaml(
            """
            name: presto
            endpoints:
              - name: "JDBC"
                node-type: app
                port: 8080
                type: jdbc
                scheme: presto
            start:
              - type: shell
                script: echo start
            stop:
              - type: shell
                script: echo stop
            capabilities:
              - type: sql
                user: easy-db-lab
            """.trimIndent(),
        )

        val groupCl = factory.buildKitGroup("presto", kitDir)
        assertThat(groupCl.subcommands.keys).contains("start", "stop", "status", "sql")
    }

    @Test
    fun `no capabilities block produces no extra subcommands`() {
        writeKitYaml(
            """
            name: mydb
            start:
              - type: shell
                script: echo start
            """.trimIndent(),
        )

        val groupCl = factory.buildKitGroup("mydb", kitDir)
        assertThat(groupCl.subcommands.keys).doesNotContain("sql")
    }
}
