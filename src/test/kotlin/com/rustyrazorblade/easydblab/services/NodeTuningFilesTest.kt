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
