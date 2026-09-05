package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.TestContextFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Verifies that `kit.yaml` alone decides which workspace subdirectories are installed kits.
 *
 * The scanner reads the filesystem and nothing else, so every case here uses real directories
 * under a temporary workspace. The `trunk` and `.venv` case is the defect this class exists to
 * prevent: both hold an executable `bin/` script and neither is a kit.
 */
class WorkspaceKitScannerTest : BaseKoinTest() {
    private lateinit var workspace: File
    private lateinit var scanner: WorkspaceKitScanner

    @BeforeEach
    fun setupScanner() {
        workspace = File(tempDir, "workspace").also { it.mkdirs() }
        scanner = WorkspaceKitScanner(TestContextFactory.createTestContext(tempDir, workingDirectory = workspace))
    }

    private fun workspaceDir(name: String): File = File(workspace, name).also { it.mkdirs() }

    private fun kitDir(name: String): File = workspaceDir(name).also { File(it, Constants.Kit.CONFIG_FILE).writeText("name: $name\n") }

    private fun binScript(
        dirName: String,
        scriptName: String,
    ): File {
        val bin = File(workspaceDir(dirName), "bin").also { it.mkdirs() }
        return File(bin, scriptName).also {
            it.writeText("#!/bin/bash\n")
            it.setExecutable(true)
        }
    }

    @Test
    fun `a directory holding only a bin script is not discovered`() {
        binScript("tools", "start.sh")

        assertThat(scanner.discover()).isEmpty()
    }

    @Test
    fun `a workspace of source checkouts and virtualenvs discovers nothing`() {
        binScript("trunk", "cassandra")
        binScript(".venv", "activate")

        assertThat(scanner.discover()).isEmpty()
    }

    @Test
    fun `a directory holding a kit descriptor is discovered`() {
        val clickhouse = kitDir("clickhouse")

        assertThat(scanner.discover()).containsExactly(clickhouse)
    }

    @Test
    fun `a directory holding both a descriptor and bin scripts is discovered once`() {
        val clickhouse = kitDir("clickhouse")
        binScript("clickhouse", "start.sh")

        assertThat(scanner.discover()).containsExactly(clickhouse)
    }

    @Test
    fun `a directory named kit yaml does not qualify its parent`() {
        val impostor = workspaceDir("impostor")
        File(impostor, Constants.Kit.CONFIG_FILE).mkdirs()

        assertThat(scanner.isInstalledKit(impostor)).isFalse()
        assertThat(scanner.discover()).isEmpty()
    }

    @Test
    fun `a plain file at the workspace root is not discovered`() {
        File(workspace, Constants.Kit.CONFIG_FILE).writeText("name: stray\n")

        assertThat(scanner.discover()).isEmpty()
    }

    @Test
    fun `a directory holding neither marker is not discovered`() {
        workspaceDir("notes")

        assertThat(scanner.discover()).isEmpty()
    }

    @Test
    fun `an empty workspace yields an empty list`() {
        assertThat(scanner.discover()).isEmpty()
    }

    @Test
    fun `a working directory that does not exist yields an empty list`() {
        // listFiles() returns null here, not an empty array. This is the case the shared
        // discover() seam exists to handle once instead of at each call site.
        val missing = File(tempDir, "never-created")
        val scannerOverMissingDir =
            WorkspaceKitScanner(TestContextFactory.createTestContext(tempDir, workingDirectory = missing))

        assertThat(scannerOverMissingDir.discover()).isEmpty()
    }
}
