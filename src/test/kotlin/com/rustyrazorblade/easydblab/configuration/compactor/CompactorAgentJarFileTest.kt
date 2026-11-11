package com.rustyrazorblade.easydblab.configuration.compactor

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Where the distribution keeps the lease agent jar, and its SHA-256 path on the control node. */
class CompactorAgentJarFileTest {
    @TempDir
    lateinit var dir: Path

    private fun jar(
        under: Path,
        content: String = "jar",
    ): Path = Files.createDirectories(under.resolve("agents")).resolve("compactor-lease.jar").also { Files.writeString(it, content) }

    @Test
    fun `the jar is found in the agents directory of the app home`() {
        val jar = jar(dir)

        assertThat(CompactorAgentJarFile.locate(agentsDir = null, appHome = dir.toString()).path).isEqualTo(jar)
    }

    @Test
    fun `an agents directory set for the container image wins over the app home`() {
        val image = Files.createDirectories(dir.resolve("image"))
        val jar = Files.writeString(image.resolve("compactor-lease.jar"), "jar")
        jar(dir.resolve("home"))

        assertThat(CompactorAgentJarFile.locate(agentsDir = image.toString(), appHome = dir.resolve("home").toString()).path).isEqualTo(jar)
    }

    @Test
    fun `a missing jar names where it was looked for`() {
        assertThatThrownBy { CompactorAgentJarFile.locate(agentsDir = null, appHome = dir.toString()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(dir.resolve("agents/compactor-lease.jar").toString())
    }

    @Test
    fun `with no app home and no agents directory the jar cannot be found`() {
        assertThatThrownBy { CompactorAgentJarFile.locate(agentsDir = null, appHome = null) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("easydblab.apphome")
    }

    @Test
    fun `the control node path is named by the jar's SHA-256, so different content gives a different path`() {
        val first = CompactorAgentJarFile(jar(dir.resolve("a"), "one"))
        val second = CompactorAgentJarFile(jar(dir.resolve("b"), "two"))

        // sha256("one")
        assertThat(first.remote.sha256).isEqualTo("7692c3ad3540bb803c020b3aee66cd8887123234ea0c6e7143c0add73ff431ed")
        assertThat(first.remote.hostPath).isEqualTo("/mnt/db1/compactor/agent/${first.remote.sha256}.jar")
        assertThat(second.remote).isNotEqualTo(first.remote)
    }
}
