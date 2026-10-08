package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.exceptions.ConfigurationException
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.services.DashboardInstallContextFactory
import com.rustyrazorblade.easydblab.services.GrafanaClient
import com.rustyrazorblade.easydblab.services.HelmService
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitHookExecutor
import com.rustyrazorblade.easydblab.services.MetricsRegistryService
import com.rustyrazorblade.easydblab.services.ObjectStore
import com.rustyrazorblade.easydblab.services.TenantDirectory
import com.rustyrazorblade.easydblab.services.WorkloadStepExecutor
import com.rustyrazorblade.easydblab.services.installConfigYaml
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import picocli.CommandLine
import java.io.File

class KitRunnerCommandFactoryTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val mockGrafanaClient: GrafanaClient = mock()
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
                single<GrafanaClient> { mockGrafanaClient }
                single { DashboardInstallContextFactory(TenantDirectory(mock<ObjectStore>())) }
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

    // -------------------------------------------------------------------------
    // per-command args registration (kit-command-args)
    // -------------------------------------------------------------------------

    @Test
    fun `command with declared args registers them as options on the subcommand`() {
        writeKitYaml(
            """
            name: kafka
            commands:
              producer-perf:
                description: "Run producer perf test"
                args:
                  - flag: --num-records
                    variable: NUM_RECORDS
                    type: int
                    default: "1000000"
                  - flag: --throughput
                    variable: THROUGHPUT
                    type: int
                    default: "-1"
            start:
              - type: shell
                script: echo start
            """.trimIndent(),
        )
        val binDir = File(kitDir, "bin").also { it.mkdirs() }
        File(binDir, "producer-perf.sh").writeText("#!/bin/sh\necho perf")

        val groupCl = factory.buildKitGroup("kafka", kitDir)
        val perfCl = groupCl.subcommands["producer-perf"]!!
        val optionNames = perfCl.commandSpec.options().map { it.longestName() }

        assertThat(optionNames).contains("--num-records", "--throughput")
    }

    @Test
    fun `a declared value arg shows a value placeholder in the command usage`() {
        writeKitYaml(
            """
            name: kafka
            commands:
              producer-perf:
                description: "Run producer perf test"
                args:
                  - flag: --num-records
                    variable: NUM_RECORDS
                    type: int
                    default: "1000000"
            start:
              - type: shell
                script: echo start
            """.trimIndent(),
        )
        File(File(kitDir, "bin").also { it.mkdirs() }, "producer-perf.sh").writeText("#!/bin/sh\necho perf")

        val perfCl = factory.buildKitGroup("kafka", kitDir).subcommands.getValue("producer-perf")

        assertThat(perfCl.usageMessage).contains("--num-records=<num-records>")
    }

    @Test
    fun `command with no commands entry has only help and name options`() {
        writeKitYaml(
            """
            name: mydb
            start:
              - type: shell
                script: echo start
            """.trimIndent(),
        )

        val groupCl = factory.buildKitGroup("mydb", kitDir)
        val startCl = groupCl.subcommands["start"]!!
        val optionNames =
            startCl.commandSpec
                .options()
                .map { it.longestName() }
                .toSet()

        assertThat(optionNames).containsExactlyInAnyOrder("--help", "--version", "--name")
    }

    // -------------------------------------------------------------------------
    // arg parsing and recording (kit-command-args: shared arg-option builder)
    // -------------------------------------------------------------------------

    /** Writes a kit whose `start` command declares [argsYaml], and returns its parsed values for [cliArgs]. */
    private fun startArgValues(
        argsYaml: String,
        vararg cliArgs: String,
    ): Map<String, String> {
        writeKitYaml(
            "name: mydb\ncommands:\n  start:\n    args:\n" + argsYaml.trimIndent().prependIndent("      ") +
                "\nstart:\n  - type: shell\n    script: echo start\n",
        )
        val group = factory.buildKitGroup("mydb", kitDir)
        group.parseArgs("start", *cliArgs)
        val startCl = group.subcommands.getValue("start")
        return (startCl.commandSpec.userObject() as KitRunnerCommand).runtimeArgValues.toMap()
    }

    @Test
    fun `a kit yaml that fails validation fails the build, naming the kit and the problem`() {
        writeKitYaml("name: mydb\nargs:\n  - flag: --env\n    variable: EXTRA_ENV\n    repeatable: true\n")

        assertThatThrownBy { factory.buildKitGroup("mydb", kitDir) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("mydb")
            .hasMessageContaining("'--env' is a top-level install arg and cannot be repeatable")
    }

    @Test
    fun `a kit yaml that does not parse fails the build, naming the kit and the file`() {
        writeKitYaml("name: mydb\nstart: [\n")

        assertThatThrownBy { factory.buildKitGroup("mydb", kitDir) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("mydb")
            .hasMessageContaining(File(kitDir, Constants.Kit.CONFIG_FILE).path)
    }

    @Test
    fun `an unset optional arg with no default is not recorded`() {
        val values = startArgValues("- flag: --image\n  variable: IMAGE")

        assertThat(values).doesNotContainKey("IMAGE")
        assertThat(values).doesNotContainValue("null")
    }

    @Test
    fun `a given optional arg is recorded verbatim`() {
        assertThat(startArgValues("- flag: --image\n  variable: IMAGE", "--image", "repo/img:1"))
            .containsEntry("IMAGE", "repo/img:1")
    }

    @Test
    fun `an omitted arg with a default injects the default`() {
        assertThat(startArgValues("- flag: --log-level\n  variable: LOG_LEVEL\n  default: info"))
            .containsEntry("LOG_LEVEL", "info")
    }

    @Test
    fun `an explicit empty arg overrides its default`() {
        assertThat(startArgValues("- flag: --log-level\n  variable: LOG_LEVEL\n  default: info", "--log-level", ""))
            .containsEntry("LOG_LEVEL", "")
    }

    @Test
    fun `an omitted boolean arg is false and a given one is true`() {
        val arg = "- flag: --heap-profile\n  variable: HEAP_PROFILE\n  type: boolean"

        assertThat(startArgValues(arg)).containsEntry("HEAP_PROFILE", "false")
        assertThat(startArgValues(arg, "--heap-profile")).containsEntry("HEAP_PROFILE", "true")
    }

    @Test
    fun `a repeated arg holds every value in order, one per line`() {
        val arg = "- flag: --env\n  variable: EXTRA_ENV\n  repeatable: true"

        assertThat(startArgValues(arg, "--env", "A=1", "--env", "B=2")).containsEntry("EXTRA_ENV", "A=1\nB=2")
    }

    @Test
    fun `a repeated arg in the attached form keeps a value that holds an equals sign`() {
        val arg = "- flag: --env\n  variable: EXTRA_ENV\n  repeatable: true"

        assertThat(startArgValues(arg, "--env=A=1", "--env=B=x=y")).containsEntry("EXTRA_ENV", "A=1\nB=x=y")
    }

    @Test
    fun `a repeatable arg with no value is a parse error`() {
        val arg = "- flag: --env\n  variable: EXTRA_ENV\n  repeatable: true"

        assertThatThrownBy { startArgValues(arg, "--env") }
            .isInstanceOf(CommandLine.MissingParameterException::class.java)
            .hasMessageContaining("--env")
    }

    @Test
    fun `a repeatable arg given once holds that value, and one not given is not recorded`() {
        val arg = "- flag: --env\n  variable: EXTRA_ENV\n  repeatable: true"

        assertThat(startArgValues(arg, "--env", "A=1")).containsEntry("EXTRA_ENV", "A=1")
        assertThat(startArgValues(arg)).doesNotContainKey("EXTRA_ENV")
    }

    /**
     * Every sysbench and kafka command arg has a default, so a command run with no flags still
     * hands each script exactly its declared defaults, as it did before the shared builder.
     */
    @Test
    fun `sysbench and kafka commands still inject their declared defaults`() {
        for (kit in listOf("sysbench", "kafka")) {
            val yaml =
                requireNotNull(javaClass.classLoader.getResource("com/rustyrazorblade/easydblab/kits/$kit/kit.yaml")).readText()
            val config = installConfigYaml.decodeFromString(KitConfig.serializer(), yaml)
            val dir = File(kitDir, kit).also { File(it, "bin").mkdirs() }
            File(dir, Constants.Kit.CONFIG_FILE).writeText(yaml)
            config.commands.keys.forEach { File(dir, "bin/$it.sh").writeText("#!/bin/sh\n") }
            val group = factory.buildKitGroup(kit, dir)

            for ((name, spec) in config.commands) {
                val cl = group.subcommands.getValue(name)
                group.parseArgs(name)

                assertThat((cl.commandSpec.userObject() as KitRunnerCommand).runtimeArgValues)
                    .describedAs("$kit $name")
                    .isEqualTo(spec.args.associate { it.variable to it.default })
            }
        }
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
