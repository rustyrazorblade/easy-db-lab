package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** [ToolWrapperInstaller] owns the wrappers in `<workspace>/bin/` and nothing else there. */
internal class ToolWrapperInstallerTest {
    @TempDir
    lateinit var workspace: File

    private val installer = ToolWrapperInstaller()
    private val bin: File get() = File(workspace, Constants.ToolWrappers.DIRECTORY)
    private val packaged: String by lazy {
        requireNotNull(javaClass.getResource(Constants.ToolWrappers.RESOURCE)).readText()
    }
    private val packagedTunnel: String by lazy {
        requireNotNull(javaClass.getResource(Constants.ToolWrappers.TUNNEL_RESOURCE)).readText()
    }

    /** Everything an install writes into `bin/`. */
    private val written = Constants.ToolWrappers.TOOLS + Constants.ToolWrappers.TUNNEL_SCRIPT + Constants.ToolWrappers.MARKER

    @Test
    fun `writes the six executable wrappers, the tunnel script and the marker`() {
        installer.install(workspace)

        assertThat(bin.list()).containsExactlyInAnyOrderElementsOf(written)
        Constants.ToolWrappers.TOOLS.forEach { tool ->
            val wrapper = File(bin, tool)
            assertThat(wrapper).hasContent(packaged)
            assertThat(wrapper.canExecute()).withFailMessage("$tool is not executable").isTrue()
        }
        val tunnel = File(bin, Constants.ToolWrappers.TUNNEL_SCRIPT)
        assertThat(tunnel).hasContent(packagedTunnel)
        assertThat(tunnel.canExecute()).withFailMessage("the tunnel script is not executable").isTrue()
    }

    @Test
    fun `a tunnel script without the marker fails, names the file, and writes nothing`() {
        bin.mkdirs()
        val foreign = File(bin, Constants.ToolWrappers.TUNNEL_SCRIPT).apply { writeText("#!/bin/sh\necho mine\n") }

        assertThatThrownBy { installer.install(workspace) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(foreign.path)

        assertThat(bin.list()).containsExactly(Constants.ToolWrappers.TUNNEL_SCRIPT)
    }

    @Test
    fun `a second run changes nothing`() {
        installer.install(workspace)
        bin.listFiles().orEmpty().forEach { it.setLastModified(LONG_AGO) }

        installer.install(workspace)

        assertThat(bin.listFiles().orEmpty().map { it.lastModified() }).containsOnly(LONG_AGO)
    }

    @Test
    fun `a wrapper whose content changed is rewritten and the others are left as they are`() {
        installer.install(workspace)
        bin.listFiles().orEmpty().forEach { it.setLastModified(LONG_AGO) }
        File(bin, "helm").writeText("#!/bin/sh\n# written by an older version\n")

        installer.install(workspace)

        assertThat(File(bin, "helm")).hasContent(packaged)
        assertThat(File(bin, "kubectl").lastModified()).isEqualTo(LONG_AGO)
    }

    @Test
    fun `a tool file without the marker fails, names the file, and writes nothing`() {
        bin.mkdirs()
        val foreign = File(bin, "kubectl").apply { writeText("#!/bin/sh\necho mine\n") }

        assertThatThrownBy { installer.install(workspace) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(foreign.path)

        assertThat(bin.list()).containsExactly("kubectl")
        assertThat(foreign).hasContent("#!/bin/sh\necho mine\n")
    }

    @Test
    fun `other files in bin without the marker do not block the wrappers`() {
        bin.mkdirs()
        File(bin, "notes.txt").writeText("kept")

        installer.install(workspace)

        assertThat(File(bin, "notes.txt")).hasContent("kept")
        assertThat(File(bin, Constants.ToolWrappers.MARKER)).exists()
    }

    @Test
    fun `remove deletes the wrappers, the marker and the then empty bin`() {
        installer.install(workspace)

        installer.remove(workspace)

        assertThat(bin).doesNotExist()
    }

    @Test
    fun `remove keeps a file it did not write, and bin with it`() {
        installer.install(workspace)
        File(bin, "notes.txt").writeText("kept")

        installer.remove(workspace)

        assertThat(bin.list()).containsExactly("notes.txt")
    }

    @Test
    fun `remove leaves a bin without the marker alone, tool names included`() {
        bin.mkdirs()
        val own = File(bin, "kubectl").apply { writeText("#!/bin/sh\necho mine\n") }

        installer.remove(workspace)

        assertThat(own).hasContent("#!/bin/sh\necho mine\n")
    }

    @Test
    fun `an install that another process completes after it found no marker still succeeds`() {
        val racing = ToolWrapperInstaller(afterMarkerFoundMissing = { ToolWrapperInstaller().install(workspace) })

        racing.install(workspace)

        assertThat(bin.list()).containsExactlyInAnyOrderElementsOf(written)
    }

    @Test
    fun `remove in a workspace with no bin does nothing`() {
        installer.remove(workspace)

        assertThat(bin).doesNotExist()
    }

    private companion object {
        const val LONG_AGO = 1_000_000_000_000L
    }
}
