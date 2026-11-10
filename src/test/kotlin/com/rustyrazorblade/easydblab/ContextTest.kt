package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Guards the [Context] rule that the workspace directory is absolute, so a relative path can
 * never make workspace files follow the process's current directory.
 */
class ContextTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a relative workspace directory is rejected with a message naming it`() {
        assertThatThrownBy {
            Context(tempDir, isInteractive = false, workingDirectory = File("relative/workspace"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be absolute")
            .hasMessageContaining("relative/workspace")
    }
}
