package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.test.get
import java.io.File

class CleanTest : BaseKoinTest() {
    @Test
    fun `clean deletes state file from working directory`() {
        // Use context's working directory from Koin
        val workingDir = context.workingDirectory

        // Create test files in working directory
        val stateFile = File(workingDir, "state.json")
        val envFile = File(workingDir, "env.sh")
        val environmentFile = File(workingDir, "environment.sh")

        stateFile.createNewFile()
        envFile.createNewFile()
        environmentFile.createNewFile()

        assertThat(stateFile).exists()
        assertThat(envFile).exists()
        assertThat(environmentFile).exists()

        // Get Clean command from Koin (it will inject context automatically)
        val clean = get<Clean>()
        clean.execute()

        assertThat(stateFile).doesNotExist()
        assertThat(envFile).doesNotExist()
        assertThat(environmentFile).doesNotExist()
    }

    @Test
    fun `clean deletes provisioning directory from working directory`() {
        val workingDir = context.workingDirectory

        // Create provisioning directory
        val provisioningDir = File(workingDir, "provisioning")
        provisioningDir.mkdir()
        File(provisioningDir, "test.txt").createNewFile()

        assertThat(provisioningDir).exists()

        val clean = get<Clean>()
        clean.execute()

        assertThat(provisioningDir).doesNotExist()
    }

    @Test
    fun `clean does not delete artifacts directory if it contains files`() {
        val workingDir = context.workingDirectory

        // Create artifacts directory with content
        val artifactsDir = File(workingDir, "artifacts")
        artifactsDir.mkdir()
        File(artifactsDir, "important.jar").createNewFile()

        assertThat(artifactsDir).exists()

        val clean = get<Clean>()
        clean.execute()

        // artifacts should still exist because it contains files
        assertThat(artifactsDir).exists()
    }

    @Test
    fun `clean deletes empty artifacts directory`() {
        val workingDir = context.workingDirectory

        // Create empty artifacts directory
        val artifactsDir = File(workingDir, "artifacts")
        artifactsDir.mkdir()

        assertThat(artifactsDir).exists()

        val clean = get<Clean>()
        clean.execute()

        // artifacts should be deleted because it's empty
        assertThat(artifactsDir).doesNotExist()
    }

    @Test
    fun `clean operates only on specified working directory`() {
        val workingDir = context.workingDirectory

        // Create a file in working directory
        val workingDirFile = File(workingDir, "state.json")
        workingDirFile.createNewFile()

        // Create another temp directory to simulate project root
        val projectRoot = File(workingDir.parentFile, "simulated-project-root")
        projectRoot.mkdir()
        val projectRootFile = File(projectRoot, "state.json")
        projectRootFile.createNewFile()

        try {
            val clean = get<Clean>()
            clean.execute()

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
        val workingDir = context.workingDirectory

        // Create ssh config file
        val sshConfig = File(workingDir, "sshConfig")
        sshConfig.createNewFile()

        assertThat(sshConfig).exists()

        val clean = get<Clean>()
        clean.execute()

        assertThat(sshConfig).doesNotExist()
    }

    @Test
    fun `clean deletes the proxy env file`() {
        val envFile = ProxyEnvFile(context.workingDirectory).apply { recordTailscale(active = false) }

        get<Clean>().execute()

        assertThat(envFile.file).doesNotExist()
    }

    @Test
    fun `clean removes the wrappers, the marker and the then empty bin`() {
        ToolWrapperInstaller().install(context.workingDirectory)

        get<Clean>().execute()

        assertThat(File(context.workingDirectory, Constants.ToolWrappers.DIRECTORY)).doesNotExist()
    }

    @Test
    fun `clean leaves a bin without the marker alone`() {
        val bin = File(context.workingDirectory, Constants.ToolWrappers.DIRECTORY).apply { mkdirs() }
        val own = File(bin, "kubectl").apply { writeText("#!/bin/sh\necho mine\n") }

        get<Clean>().execute()

        assertThat(own).hasContent("#!/bin/sh\necho mine\n")
    }

    @Test
    fun `clean keeps a file in bin it did not write, and bin with it`() {
        ToolWrapperInstaller().install(context.workingDirectory)
        val bin = File(context.workingDirectory, Constants.ToolWrappers.DIRECTORY)
        File(bin, "my-script").writeText("#!/bin/sh\n")

        get<Clean>().execute()

        assertThat(bin.list()).containsExactly("my-script")
    }
}
