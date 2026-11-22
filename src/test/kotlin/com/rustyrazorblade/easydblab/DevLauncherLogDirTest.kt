package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Runs the dev wrapper `bin/easy-db-lab` from an empty directory against a stub generated launcher.
 * The wrapper must put its log directory where the packaged logback does (under the user
 * directory), never in the process's current directory: a command creates no file in a directory
 * that is not the workspace.
 */
class DevLauncherLogDirTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `the default log directory is under the user directory, not the current directory`() {
        val appHome = File(tempDir, "app")
        File(DEV_LAUNCHER).copyTo(File(appHome, "bin/easy-db-lab")).setExecutable(true)
        File(appHome, "build/install/easy-db-lab/bin/easy-db-lab").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\necho \"LOG_DIR=${'$'}EASY_DB_LAB_LOG_DIR\"\n")
            setExecutable(true)
        }
        val userDir = File(tempDir, "user")
        val cwd = File(tempDir, "elsewhere").also { it.mkdirs() }
        val output = File(tempDir, "launcher.out")

        val exit =
            ProcessBuilder("bash", File(appHome, "bin/easy-db-lab").absolutePath, "version")
                .directory(cwd)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .also { pb ->
                    val env = pb.environment()
                    env.keys.removeIf { it.startsWith("EASY_DB_LAB_") }
                    env["EASY_DB_LAB_USER_DIR"] = userDir.absolutePath
                }.start()
                .waitFor()

        assertThat(exit).describedAs(output.readText()).isZero()
        assertThat(cwd.list()).isEmpty()
        assertThat(output.readText()).contains("LOG_DIR=${File(userDir, "logs").absolutePath}")
        assertThat(File(userDir, "logs")).isDirectory()
    }

    private companion object {
        const val DEV_LAUNCHER = "bin/easy-db-lab"
    }
}
