package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** [PackagedExecutable] writes a script so that a reader never sees it half-written or not executable. */
internal class PackagedExecutableTest {
    @TempDir
    lateinit var dir: File

    private val target: File get() = File(dir, "bin/tool")

    @Test
    fun `writes the content into a new directory and makes it executable`() {
        val wrote = PackagedExecutable(SCRIPT).writeTo(target)

        assertThat(wrote).isTrue()
        assertThat(target).hasContent(SCRIPT)
        assertThat(target.canExecute()).isTrue()
    }

    @Test
    fun `leaves a file that already holds the content untouched`() {
        PackagedExecutable(SCRIPT).writeTo(target)
        target.setLastModified(LONG_AGO)

        val wrote = PackagedExecutable(SCRIPT).writeTo(target)

        assertThat(wrote).isFalse()
        assertThat(target.lastModified()).isEqualTo(LONG_AGO)
    }

    @Test
    fun `replaces a file whose content changed`() {
        PackagedExecutable("#!/bin/sh\necho old\n").writeTo(target)

        val wrote = PackagedExecutable(SCRIPT).writeTo(target)

        assertThat(wrote).isTrue()
        assertThat(target).hasContent(SCRIPT)
        assertThat(target.canExecute()).isTrue()
    }

    @Test
    fun `rewrites a file with the right content that is not executable`() {
        target.parentFile.mkdirs()
        target.writeText(SCRIPT)

        val wrote = PackagedExecutable(SCRIPT).writeTo(target)

        assertThat(wrote).isTrue()
        assertThat(target.canExecute()).isTrue()
    }

    @Test
    fun `stages to a unique temporary file beside the target and renames it into place`() {
        val staged = mutableListOf<File>()
        val executable =
            PackagedExecutable(SCRIPT) { file ->
                staged += file
                file.setExecutable(true, true)
            }

        executable.writeTo(target)
        target.delete()
        executable.writeTo(target)

        assertThat(staged).hasSize(2)
        assertThat(staged).allSatisfy { assertThat(it.parentFile).isEqualTo(target.parentFile) }
        assertThat(staged).noneMatch { it == target }
        assertThat(staged[0]).isNotEqualTo(staged[1])
        assertThat(staged).allSatisfy { assertThat(it).doesNotExist() }
        assertThat(target.parentFile.list()).containsExactly("tool")
    }

    @Test
    fun `fails and leaves nothing behind when the file cannot be made executable`() {
        assertThatThrownBy { PackagedExecutable(SCRIPT) { false }.writeTo(target) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("executable")

        assertThat(target).doesNotExist()
        assertThat(target.parentFile.list()).isEmpty()
    }

    @Test
    fun `reads its content from a packaged resource`() {
        val executable = PackagedExecutable.fromResource("/com/rustyrazorblade/easydblab/ssm/edl-ssm-proxy.sh")

        executable.writeTo(target)

        assertThat(target.readText()).startsWith("#!/bin/sh")
    }

    @Test
    fun `fails on a resource missing from the distribution`() {
        assertThatThrownBy { PackagedExecutable.fromResource("/no/such/resource.sh") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("/no/such/resource.sh")
    }

    private companion object {
        const val SCRIPT = "#!/bin/sh\necho new\n"
        const val LONG_AGO = 1_000_000_000_000L
    }
}
