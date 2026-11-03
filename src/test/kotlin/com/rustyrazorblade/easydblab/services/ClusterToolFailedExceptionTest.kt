package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** What a failed helm or kubectl call tells the user, in either [ClusterTooling] form. */
internal class ClusterToolFailedExceptionTest {
    @Test
    fun `the message names the tool, the arguments, the status and the stderr`() {
        val failure =
            ClusterToolFailedException(
                tool = ClusterTool.KUBECTL,
                args = listOf("get", "nosuchkind"),
                exitStatus = 1,
                stderr = "error: the server doesn't have a resource type \"nosuchkind\"\n",
            )

        assertThat(failure.message)
            .contains("kubectl get nosuchkind", "exit status 1", "the server doesn't have a resource type \"nosuchkind\"")
        assertThat(failure.stderr).isEqualTo("error: the server doesn't have a resource type \"nosuchkind\"")
    }

    @Test
    fun `where the tool ran appears in the message only when given`() {
        val local = ClusterToolFailedException(ClusterTool.HELM, listOf("list"), 1, "boom")
        val remote = ClusterToolFailedException(ClusterTool.HELM, listOf("list"), 1, "boom", location = "control0 (54.1.2.3)")

        assertThat(local.message).doesNotContain(" on ")
        assertThat(remote.message).contains("on control0 (54.1.2.3)")
    }

    @Test
    fun `stderr keeps only the last lines up to the tail limit`() {
        val limit = Constants.ClusterTooling.FAILURE_TAIL_LINES
        val stderr = (1..limit + 20).joinToString("\n") { "line $it" }

        val failure = ClusterToolFailedException(ClusterTool.HELM, listOf("upgrade"), 1, stderr)

        val kept = failure.stderr.lines()
        assertThat(kept).hasSize(limit)
        assertThat(kept.first()).isEqualTo("line 21")
        assertThat(kept.last()).isEqualTo("line ${limit + 20}")
        assertThat(failure.message).doesNotContain("line 20\n")
    }

    @Test
    fun `credentials in a url never reach the message or the stderr`() {
        val failure =
            ClusterToolFailedException(
                tool = ClusterTool.KUBECTL,
                args = listOf("apply", "-f", "https://alice:ghp_secrettoken@example.com/m.yaml"),
                exitStatus = 1,
                stderr = "error: unable to read URL \"https://alice:ghp_secrettoken@example.com/m.yaml\"",
            )

        assertThat(failure.message).doesNotContain("ghp_secrettoken").contains("***@example.com")
        assertThat(failure.stderr).doesNotContain("ghp_secrettoken").contains("***@example.com")
    }

    @Test
    fun `the exception carries no cause that could repeat an unredacted command`() {
        val failure = ClusterToolFailedException(ClusterTool.HELM, listOf("list"), 1, "boom")

        assertThat(failure.cause).isNull()
    }
}
