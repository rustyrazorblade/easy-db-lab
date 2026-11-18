package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path

/**
 * The node tuning files node setup uploads: the packaged list matches the packaged directory, and
 * staging copies them byte for byte into a directory that is gone after the upload.
 */
class NodeTuningFilesTest {
    private fun packagedDir(): File {
        val url = checkNotNull(javaClass.getResource(Constants.NodeTuning.RESOURCE_DIR)) { "no packaged node-tuning directory" }
        return Path.of(url.toURI()).toFile()
    }

    @Test
    fun `the staged list is every file in the packaged directory`() {
        assertThat(Constants.NodeTuning.FILES)
            .containsExactlyInAnyOrderElementsOf(packagedDir().list()!!.toList())
    }

    @Test
    fun `staging copies every packaged file and removes the copy afterwards`() {
        var staged: File? = null
        val contents =
            NodeTuningFiles().stage { dir ->
                staged = dir
                dir.listFiles()!!.associate { it.name to it.readText() }
            }

        val expected = packagedDir().listFiles()!!.associate { it.name to it.readText() }
        assertThat(contents).isEqualTo(expected)
        assertThat(staged!!).doesNotExist()
    }

    // The unit's ExecStartPost verify reads irqbalance's state, so at boot the unit must start
    // after irqbalance, or every boot logs a false irqbalance drift line.
    @Test
    fun `the boot unit verifies only after irqbalance has started`() {
        val after =
            File(packagedDir(), "edl-node-tuning.service")
                .readLines()
                .filter { it.startsWith("After=") }
                .flatMap { it.substringAfter("=").split(" ") }
        assertThat(after).contains("irqbalance.service")
    }

    // udev renames an interface in 80-net-setup-link.rules. A rules file that sorts before it sees
    // the old name, so a hot-attached ENI would get no packet steering.
    @Test
    fun `the udev rules run after udev has named the interface`() {
        val rules = packagedDir().list()!!.filter { it.endsWith(".rules") }
        assertThat(rules).isNotEmpty.allSatisfy { assertThat(it).isGreaterThan("80-net-setup-link.rules") }
    }

    @Test
    fun `a file missing from the distribution fails before the upload, naming it`() {
        var uploaded = false
        val files = NodeTuningFiles(files = listOf(Constants.NodeTuning.SYSCTL_FILE, "absent.conf"))

        assertThatThrownBy { files.stage { uploaded = true } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("${Constants.NodeTuning.RESOURCE_DIR}/absent.conf")
        assertThat(uploaded).isFalse()
    }
}
