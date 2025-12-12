package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.TestContextFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CleanTest : BaseKoinTest() {
    @TempDir
    lateinit var workingDir: File

    @Test
    fun `clean deletes state file from working directory`() {
        // Create test files in temp dir
        val stateFile = File(workingDir, "state.json")
        val envFile = File(workingDir, "env.sh")
        val environmentFile = File(workingDir, "environment.sh")

        stateFile.createNewFile()
        envFile.createNewFile()
        environmentFile.createNewFile()

        assertThat(stateFile).exists()
        assertThat(envFile).exists()
        assertThat(environmentFile).exists()

        val testContext = TestContextFactory.createTestContext(tempDir, workingDirectory = workingDir)
        Clean(testContext).execute()

        assertThat(stateFile).doesNotExist()
        assertThat(envFile).doesNotExist()
        assertThat(environmentFile).doesNotExist()
    }

    @Test
    fun `clean deletes provisioning directory from working directory`() {
        // Create provisioning directory
        val provisioningDir = File(workingDir, "provisioning")
        provisioningDir.mkdir()
        File(provisioningDir, "test.txt").createNewFile()

        assertThat(provisioningDir).exists()

        val testContext = TestContextFactory.createTestContext(tempDir, workingDirectory = workingDir)
        Clean(testContext).execute()

        assertThat(provisioningDir).doesNotExist()
    }

    @Test
    fun `clean does not delete artifacts directory if it contains files`() {
        // Create artifacts directory with content
        val artifactsDir = File(workingDir, "artifacts")
        artifactsDir.mkdir()
        File(artifactsDir, "important.jar").createNewFile()

        assertThat(artifactsDir).exists()

        val testContext = TestContextFactory.createTestContext(tempDir, workingDirectory = workingDir)
        Clean(testContext).execute()

        // artifacts should still exist because it contains files
        assertThat(artifactsDir).exists()
    }

    @Test
    fun `clean deletes empty artifacts directory`() {
        // Create empty artifacts directory
        val artifactsDir = File(workingDir, "artifacts")
        artifactsDir.mkdir()

        assertThat(artifactsDir).exists()

        val testContext = TestContextFactory.createTestContext(tempDir, workingDirectory = workingDir)
        Clean(testContext).execute()

        // artifacts should be deleted because it's empty
        assertThat(artifactsDir).doesNotExist()
    }

    @Test
    fun `clean operates only on specified working directory`() {
        // Create a file in working directory
        val workingDirFile = File(workingDir, "state.json")
        workingDirFile.createNewFile()

        // Create another temp directory to simulate project root
        val projectRoot = File(workingDir.parentFile, "simulated-project-root")
        projectRoot.mkdir()
        val projectRootFile = File(projectRoot, "state.json")
        projectRootFile.createNewFile()

        try {
            val testContext = TestContextFactory.createTestContext(tempDir, workingDirectory = workingDir)
            Clean(testContext).execute()

            // Working directory file should be deleted
            assertThat(workingDirFile).doesNotExist()

            // Project root file should NOT be deleted (different directory)
            assertThat(projectRootFile).exists()
        } finally {
            // Cleanup
            projectRootFile.delete()
            projectRoot.delete()
        }
    }

    @Test
    fun `clean deletes ssh config file`() {
        // Create ssh config file
        val sshConfig = File(workingDir, "sshConfig")
        sshConfig.createNewFile()

        assertThat(sshConfig).exists()

        val testContext = TestContextFactory.createTestContext(tempDir, workingDirectory = workingDir)
        Clean(testContext).execute()

        assertThat(sshConfig).doesNotExist()
    }
}
