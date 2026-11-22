package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.NetworkMode
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitArgSpec
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.KitProcessEnvironment
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.TemplateVariables
import com.rustyrazorblade.easydblab.services.WorkloadStepExecutor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.assertj.core.api.Assumptions.assumeThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Tests that KitInstallCommand creates the right directory name based on kit-ref args.
 */
class KitInstallCommandTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val mockResolver: InstallTemplateResolver = mock()

    @TempDir
    lateinit var templateDir: File

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single { TemplateService(get(), get()) }
                single<InstallTemplateResolver> { mockResolver }
                single {
                    WorkloadStepExecutor(
                        mock(),
                        mock(),
                        mock(),
                        get(),
                        get(),
                        HostOperationsService(get()),
                        KitProcessEnvironment(ToolWrapperInstaller()),
                    )
                }
            },
        )

    private val clusterState =
        ClusterState(
            name = "test-cluster",
            s3Bucket = "test-bucket",
            initConfig = InitConfig(region = "us-west-2", name = "test-cluster"),
            hosts =
                mapOf(
                    ServerType.Control to listOf(ClusterHost("1.2.3.4", "10.0.0.1", "control0", "us-west-2a", "i-ctrl")),
                    ServerType.Stress to listOf(ClusterHost("2.3.4.5", "10.0.1.1", "app0", "us-west-2a", "i-app0")),
                    ServerType.Cassandra to listOf(ClusterHost("3.4.5.6", "10.0.2.1", "db0", "us-west-2a", "i-db0")),
                ),
        )

    private val sysbenchConfig =
        KitConfig(
            name = "sysbench",
            type = null,
            args =
                listOf(
                    KitArgSpec(
                        flag = "--target",
                        variable = "TARGET",
                        type = KitArgSpec.ArgType.KIT_REF,
                    ),
                ),
        )

    private lateinit var factory: KitInstallCommandFactory
    private lateinit var source: InstallTemplateResolver.TemplateSource.Directory
    private lateinit var workingDir: File

    @BeforeEach
    fun setup() {
        workingDir = get<Context>().workingDirectory
        File(workingDir, Constants.K3s.LOCAL_KUBECONFIG).writeText("apiVersion: v1\nkind: Config\n")
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)
        whenever(mockResolver.listTemplateFiles(org.mockito.kotlin.any())).thenReturn(emptyList())
        whenever(mockResolver.readInstallYamlContent(org.mockito.kotlin.any())).thenReturn(null)
        val templateVars = TemplateVariables.from(state = clusterState, workspace = workingDir, kitName = "", storageSize = "").toMap()
        factory = KitInstallCommandFactory(templateVars)
        source = InstallTemplateResolver.TemplateSource.Directory(templateDir)
    }

    private fun buildAndRun(
        config: KitConfig,
        argValues: Map<String, String>,
    ) {
        val cl = factory.build(config, source)
        val cmd = cl.commandSpec.userObject() as KitInstallCommand
        argValues.forEach { (k, v) -> cmd.argValues[k] = v }
        cmd.execute()
    }

    @Test
    fun `kit without kit-ref arg creates directory named after kit`() {
        val config = KitConfig(name = "mydb", type = null)
        buildAndRun(config, emptyMap())
        assertThat(File(workingDir, "mydb")).isDirectory()
    }

    @Test
    fun `kit with kit-ref arg creates directory named kit-target`() {
        buildAndRun(sysbenchConfig, mapOf("TARGET" to "clickhouse"))
        assertThat(File(workingDir, "sysbench-clickhouse")).isDirectory()
        assertThat(File(workingDir, "sysbench")).doesNotExist()
    }

    @Test
    fun `two installs with different targets create separate directories`() {
        buildAndRun(sysbenchConfig, mapOf("TARGET" to "clickhouse"))
        buildAndRun(sysbenchConfig, mapOf("TARGET" to "mysql"))
        assertThat(File(workingDir, "sysbench-clickhouse")).isDirectory()
        assertThat(File(workingDir, "sysbench-mysql")).isDirectory()
    }

    @Test
    fun `kit-ref with capability passes when target has that capability`() {
        val benchConfig =
            KitConfig(
                name = "sysbench",
                type = null,
                args =
                    listOf(
                        KitArgSpec(flag = "--target", variable = "TARGET", type = KitArgSpec.ArgType.KIT_REF, capability = "sql"),
                    ),
            )
        // Write a target kit with the sql capability
        val targetDir = File(workingDir, "tidb").also { it.mkdirs() }
        File(targetDir, "kit.yaml").writeText(
            """
            name: tidb
            capabilities:
              - type: sql
                user: root
            """.trimIndent(),
        )

        buildAndRun(benchConfig, mapOf("TARGET" to "tidb"))
        assertThat(File(workingDir, "sysbench-tidb")).isDirectory()
    }

    @Test
    fun `kit-ref with capability fails when target lacks that capability`() {
        val benchConfig =
            KitConfig(
                name = "sysbench",
                type = null,
                args =
                    listOf(
                        KitArgSpec(flag = "--target", variable = "TARGET", type = KitArgSpec.ArgType.KIT_REF, capability = "sql"),
                    ),
            )
        val targetDir = File(workingDir, "presto").also { it.mkdirs() }
        File(targetDir, "kit.yaml").writeText("name: presto\n")

        assertThatThrownBy { buildAndRun(benchConfig, mapOf("TARGET" to "presto")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("sql")
            .hasMessageContaining("presto")
    }

    @Test
    fun `kit-ref with endpoints fails before writing when the target lacks one`() {
        val benchConfig =
            KitConfig(
                name = "clickhouse-benchmark",
                type = null,
                args =
                    listOf(
                        KitArgSpec(
                            flag = "--target",
                            variable = "TARGET",
                            type = KitArgSpec.ArgType.KIT_REF,
                            endpoints = listOf(KitEndpoint.EndpointType.NATIVE, KitEndpoint.EndpointType.HTTP),
                        ),
                    ),
            )
        val targetDir = File(workingDir, "tidb").also { it.mkdirs() }
        File(targetDir, "kit.yaml").writeText(
            """
            name: tidb
            endpoints:
              - name: HTTP
                node-type: db
                port: 10080
                type: http
            """.trimIndent(),
        )

        assertThatThrownBy { buildAndRun(benchConfig, mapOf("TARGET" to "tidb")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Target kit 'tidb' does not have the 'native' endpoint required by clickhouse-benchmark")
        assertThat(File(workingDir, "clickhouse-benchmark-tidb")).doesNotExist()
    }

    private val extensionsYaml =
        """
        extensions:
          duckdb:
            description: "DuckDB"
            image: "ghcr.io/duckdb/pg_duckdb:pg__PG_MAJOR__"
            shared_preload_libraries:
              - pg_duckdb
            create_extensions:
              - pg_duckdb
        """.trimIndent()

    private val postgresConfig =
        KitConfig(
            name = "postgres",
            type = null,
            args =
                listOf(
                    KitArgSpec(flag = "--version", variable = "POSTGRES_VERSION", type = KitArgSpec.ArgType.STRING, default = "17"),
                    KitArgSpec(flag = "--extension", variable = "EXTENSION", type = KitArgSpec.ArgType.EXTENSION, default = ""),
                ),
        )

    @Test
    fun `extension arg creates directory named kit-extension`() {
        File(templateDir, "extensions.yaml").writeText(extensionsYaml)
        buildAndRun(postgresConfig, mapOf("POSTGRES_VERSION" to "17", "EXTENSION" to "duckdb"))
        assertThat(File(workingDir, "postgres-duckdb")).isDirectory()
        assertThat(File(workingDir, "postgres")).doesNotExist()
    }

    @Test
    fun `no extension arg creates directory named after kit`() {
        File(templateDir, "extensions.yaml").writeText(extensionsYaml)
        buildAndRun(postgresConfig, mapOf("POSTGRES_VERSION" to "17"))
        assertThat(File(workingDir, "postgres")).isDirectory()
    }

    @Test
    fun `extension arg bakes IMAGE into resolved args`() {
        File(templateDir, "extensions.yaml").writeText(extensionsYaml)
        buildAndRun(postgresConfig, mapOf("POSTGRES_VERSION" to "17", "EXTENSION" to "duckdb"))
        val resolvedArgs =
            File(workingDir, "postgres-duckdb/${Constants.Kit.RESOLVED_ARGS_FILE}")
                .readLines()
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        assertThat(resolvedArgs["IMAGE"]).isEqualTo("ghcr.io/duckdb/pg_duckdb:pg17")
    }

    @Test
    fun `no extension bakes default IMAGE into resolved args`() {
        File(templateDir, "extensions.yaml").writeText(extensionsYaml)
        buildAndRun(postgresConfig, mapOf("POSTGRES_VERSION" to "17"))
        val resolvedArgs =
            File(workingDir, "postgres/${Constants.Kit.RESOLVED_ARGS_FILE}")
                .readLines()
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        assertThat(resolvedArgs["IMAGE"]).isEqualTo("ghcr.io/cloudnative-pg/postgresql:17")
    }

    /**
     * A kit-ref or extension value becomes part of a workspace directory name, so a value that is
     * not a kit name is refused before anything is read or written. `x/../clickhouse` would
     * otherwise normalize onto the installed clickhouse kit, and `--force` would replace it.
     */
    @ParameterizedTest(name = "[{0}] {1}")
    @CsvSource(
        "TARGET, x/../clickhouse",
        "TARGET, ../x",
        "TARGET, a/b",
        "TARGET, my kit",
        "TARGET, -x",
        "EXTENSION, x/../clickhouse",
        "EXTENSION, my kit",
    )
    fun `a kit-ref or extension value that is not a kit name is refused and leaves every kit alone`(
        variable: String,
        value: String,
    ) {
        File(templateDir, "extensions.yaml").writeText(extensionsYaml)
        val clickhouse = File(workingDir, "clickhouse").apply { mkdirs() }
        File(clickhouse, Constants.Kit.CONFIG_FILE).writeText("name: clickhouse\n")
        val config =
            KitConfig(
                name = "bench",
                type = null,
                args =
                    listOf(
                        KitArgSpec(flag = "--target", variable = "TARGET", type = KitArgSpec.ArgType.KIT_REF),
                        KitArgSpec(flag = "--extension", variable = "EXTENSION", type = KitArgSpec.ArgType.EXTENSION, default = ""),
                    ),
            )
        val cl = factory.build(config, source)
        val cmd = cl.commandSpec.userObject() as KitInstallCommand
        cmd.argValues[variable] = value
        cmd.force = true

        assertThatThrownBy { cmd.execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid kit name '$value'")
        assertThat(File(clickhouse, Constants.Kit.CONFIG_FILE)).hasContent("name: clickhouse")
        assertThat(workingDir.list().orEmpty().filter { it.startsWith("bench") }).isEmpty()
    }

    @Test
    fun `kit-ref fails when target kit is not installed`() {
        val benchConfig =
            KitConfig(
                name = "sysbench",
                type = null,
                args =
                    listOf(
                        KitArgSpec(flag = "--target", variable = "TARGET", type = KitArgSpec.ArgType.KIT_REF, capability = "sql"),
                    ),
            )

        assertThatThrownBy { buildAndRun(benchConfig, mapOf("TARGET" to "doesnotexist")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("doesnotexist")
            .hasMessageContaining("not installed")
    }

    @Test
    fun `resolved args omit STORAGE_SIZE for a kit that declares no storage arg`() {
        val config =
            KitConfig(
                name = "cache",
                type = null,
                args = listOf(KitArgSpec(flag = "--memory", variable = "MEMORY_MB", type = KitArgSpec.ArgType.INT, default = "1024")),
            )

        buildAndRun(config, emptyMap())

        assertThat(resolvedArgs("cache"))
            .containsEntry("MEMORY_MB", "1024")
            .doesNotContainKey("STORAGE_SIZE")
    }

    @Test
    fun `resolved args keep the STORAGE_SIZE a kit declares`() {
        val config =
            KitConfig(
                name = "store",
                type = null,
                args = listOf(KitArgSpec(flag = "--size", variable = "STORAGE_SIZE", type = KitArgSpec.ArgType.STRING, default = "10Ti")),
            )

        buildAndRun(config, mapOf("STORAGE_SIZE" to "100Gi"))

        assertThat(resolvedArgs("store")).containsEntry("STORAGE_SIZE", "100Gi")
    }

    @TestFactory
    fun `an install arg value with a line break is rejected and writes no kit directory`(): List<DynamicTest> =
        listOf("a\nINJECTED=1", "a\rINJECTED=1", "a\r\nINJECTED=1").map { value ->
            DynamicTest.dynamicTest(value.replace("\r", "CR").replace("\n", "LF")) {
                val config =
                    KitConfig(name = "br", type = null, args = listOf(KitArgSpec(flag = "--note", variable = "NOTE")))

                assertThatThrownBy { parseAndRun(config, "--note", value) }
                    .hasMessageContaining("NOTE")
                    .hasMessageContaining("line break")
                assertThat(File(workingDir, "br")).doesNotExist()
            }
        }

    /** A scaffold cleanup that fails as well is attached to the install error, which stays the reported cause. */
    @Test
    fun `a failed install whose scaffold cleanup also fails reports the install error`() {
        // Root deletes a file in a read-only directory, so the cleanup would not fail.
        assumeThat(System.getProperty("user.name")).isNotEqualTo("root")
        var locked: File? = null
        whenever(mockResolver.readInstallYamlContent(org.mockito.kotlin.any())).thenAnswer {
            val scaffold = workingDir.listFiles().orEmpty().single { it.name.startsWith(".tmp-plain-") }
            locked = File(scaffold, "locked").apply { mkdirs() }
            File(scaffold, "locked/f").writeText("x")
            File(scaffold, "locked").setWritable(false)
            error("render failed")
        }
        try {
            val thrown = catchThrowable { buildAndRun(KitConfig(name = "plain", type = null), emptyMap()) }

            assertThat(thrown).isInstanceOf(IllegalStateException::class.java).hasMessage("render failed")
            assertThat(thrown.suppressed.single()).isInstanceOf(IOException::class.java)
        } finally {
            locked?.setWritable(true)
        }
    }

    /** Parses [args] as the user would type them, then runs the install. */
    private fun parseAndRun(
        config: KitConfig,
        vararg args: String,
    ) {
        val cl = factory.build(config, source)
        cl.parseArgs(*args)
        (cl.commandSpec.userObject() as KitInstallCommand).execute()
    }

    @Test
    fun `an install arg with no value and no default is recorded as an empty string`() {
        val config =
            KitConfig(name = "pg", type = null, args = listOf(KitArgSpec(flag = "--extension", variable = "EXTENSION")))

        parseAndRun(config)

        assertThat(File(workingDir, "pg/${Constants.Kit.RESOLVED_ARGS_FILE}").readLines()).contains("EXTENSION=")
    }

    @Test
    fun `an unresolved default is kept rather than replaced by an empty string`() {
        val config =
            KitConfig(
                name = "sized",
                type = null,
                args = listOf(KitArgSpec(flag = "--nodes", variable = "NODES", default = "\${NOT_A_CLUSTER_VAR}")),
            )

        parseAndRun(config)

        assertThat(resolvedArgs("sized")).containsEntry("NODES", "\${NOT_A_CLUSTER_VAR}")
    }

    private val cacheConfig =
        KitConfig(
            name = "cache",
            type = null,
            args =
                listOf(
                    KitArgSpec(
                        flag = "--s3-cache-on-write",
                        variable = "S3_CACHE_ON_WRITE",
                        type = KitArgSpec.ArgType.BOOLEAN,
                        default = "true",
                    ),
                ),
        )

    @Test
    fun `a boolean install arg given false as a separate value records false`() {
        parseAndRun(cacheConfig, "--s3-cache-on-write", "false")

        assertThat(resolvedArgs("cache")).containsEntry("S3_CACHE_ON_WRITE", "false")
    }

    @Test
    fun `a boolean install arg given false with an equals sign records false`() {
        parseAndRun(cacheConfig, "--s3-cache-on-write=false")

        assertThat(resolvedArgs("cache")).containsEntry("S3_CACHE_ON_WRITE", "false")
    }

    @Test
    fun `a bare boolean install arg records true`() {
        parseAndRun(cacheConfig, "--s3-cache-on-write")

        assertThat(resolvedArgs("cache")).containsEntry("S3_CACHE_ON_WRITE", "true")
    }

    @Test
    fun `a failed install step exits non-zero and removes the kit directory instead of throwing`() {
        val config = KitConfig(name = "broken", type = null, install = listOf(InstallStep.Shell("exit 2")))
        val cmd = factory.build(config, source).commandSpec.userObject() as KitInstallCommand

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(File(workingDir, "broken")).doesNotExist()
    }

    @Test
    fun `an install shell step gets the absolute workspace kubeconfig and the workspace bin first on PATH`() {
        val seen = File(workingDir, "seen.txt")
        val config =
            KitConfig(
                name = "envcheck",
                type = null,
                install =
                    listOf(
                        InstallStep.Shell(
                            "echo \"\$KUBECONFIG\" > '${seen.absolutePath}'\necho \"\$PATH\" >> '${seen.absolutePath}'\n" +
                                "echo \"\$${Constants.Kit.LIBRARY_ENV}\" >> '${seen.absolutePath}'",
                        ),
                    ),
            )

        val exitCode = (factory.build(config, source).commandSpec.userObject() as KitInstallCommand).call()

        assertThat(exitCode).isZero()
        val (kubeconfig, path, library) = seen.readLines()
        val bin = File(workingDir, Constants.ToolWrappers.DIRECTORY)
        assertThat(kubeconfig).isEqualTo(File(workingDir, Constants.K3s.LOCAL_KUBECONFIG).absolutePath)
        assertThat(path.split(File.pathSeparator).first()).isEqualTo(bin.absolutePath)
        assertThat(library).isEqualTo(File(bin, Constants.ToolWrappers.KIT_LIBRARY).absolutePath)
    }

    /**
     * The bug this change fixes: an install shell step got the relative `KUBECONFIG=kubeconfig`,
     * which resolved against the kit directory, where there is none. The stub `kubectl`, after the
     * wrappers on `PATH`, fails unless the kubeconfig it is handed exists and it was reached
     * through the wrapper with the recorded tunnel port.
     */
    @Test
    fun `kubectl get ns in an install shell step succeeds, so kit install exits 0`() {
        ProxyEnvFile(workingDir).apply {
            recordNetworkMode(NetworkMode.Socks)
            recordTunnel(41234, ProcessHandle.current().pid())
        }
        val stubs =
            File(tempDir, "real-bin").apply {
                mkdirs()
                File(this, "kubectl").apply {
                    writeText(
                        """
                        |#!/bin/sh
                        |[ -f "${'$'}KUBECONFIG" ] || { echo "no kubeconfig at ${'$'}KUBECONFIG" >&2; exit 1; }
                        |[ "${'$'}HTTPS_PROXY" = socks5://127.0.0.1:41234 ] || { echo "not through the tunnel" >&2; exit 1; }
                        |echo "default Active"
                        |
                        """.trimMargin(),
                    )
                    setExecutable(true)
                }
            }
        val config =
            KitConfig(
                name = "needs-kubectl",
                type = null,
                // The stub goes right after the wrappers, ahead of any kubectl installed on this machine.
                install = listOf(InstallStep.Shell("PATH=\"\${PATH%%:*}:${stubs.absolutePath}:\$PATH\" kubectl get ns")),
            )

        val exitCode = (factory.build(config, source).commandSpec.userObject() as KitInstallCommand).call()

        assertThat(exitCode).isZero()
        assertThat(File(workingDir, "needs-kubectl")).isDirectory()
    }

    @Test
    fun `a forced reinstall over a kit directory that is a link replaces the link and keeps its target`() {
        val outside = File(tempDir, "kit-checkout").apply { mkdirs() }
        val precious = File(outside, "precious.txt").apply { writeText("source") }
        val link = File(workingDir, "mydb")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val cmd = factory.build(KitConfig(name = "mydb", type = null), source).commandSpec.userObject() as KitInstallCommand
        cmd.force = true

        cmd.execute()

        assertThat(precious).hasContent("source")
        assertThat(Files.isSymbolicLink(link.toPath())).isFalse()
        assertThat(File(link, Constants.Kit.RESOLVED_ARGS_FILE)).isFile()
    }

    private fun resolvedArgs(kitDir: String): Map<String, String> =
        File(workingDir, "$kitDir/${Constants.Kit.RESOLVED_ARGS_FILE}")
            .readLines()
            .filter { it.isNotBlank() }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
}
