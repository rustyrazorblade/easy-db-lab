package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.DefaultKitCommandScanner
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitCommandScanner
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import com.rustyrazorblade.easydblab.services.WorkspaceKitScanner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Proves that `easy-db-lab --help` lists exactly the workspace directories holding a `kit.yaml`.
 *
 * This is the end-to-end guard for the registration path. `WorkspaceKitScannerTest` proves the
 * rule; this proves the parser actually applies it, so re-inlining a `bin/` predicate or swapping
 * the scanner for a local `listFiles()` walk fails a test instead of silently restoring the
 * defect that flooded help output with Cassandra checkouts.
 *
 * Every collaborator is the real implementation. All three read the filesystem or the classpath
 * and have no external side effects, and the whole point of the test is the wiring between them.
 */
class CommandLineParserTest : BaseKoinTest() {
    private val stdout = ByteArrayOutputStream()
    private val originalOut = System.out

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { WorkspaceKitScanner(get()) }
                single { KitSourcesProvider(get()) }
                single { InstallTemplateResolver(get(), get()) }
                single<KitCommandScanner> { DefaultKitCommandScanner() }
                single { ClusterStateManager(File(get<Context>().workingDirectory, "state.json")) }
            },
        )

    @BeforeEach
    fun captureStdout() {
        System.setOut(PrintStream(stdout))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
        stdout.reset()
    }

    @Test
    fun `help lists a directory holding a kit descriptor and omits a bin-only checkout`() {
        val workspace = context.workingDirectory
        File(workspace, "clickhouse").mkdirs()
        File(File(workspace, "clickhouse"), Constants.Kit.CONFIG_FILE).writeText("name: clickhouse\n")
        File(File(workspace, "trunk"), "bin").mkdirs()
        File(File(File(workspace, "trunk"), "bin"), "cassandra").also {
            it.writeText("#!/bin/bash\n")
            it.setExecutable(true)
        }

        // --help exits 0, so eval() returns rather than reaching exitProcess.
        CommandLineParser().eval(arrayOf("--help"))

        val output = stdout.toString()
        assertThat(output).contains("clickhouse")
        assertThat(output).doesNotContain("trunk")
    }
}
