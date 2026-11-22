package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** What a failed helm or kubectl call tells the user. */
internal class ClusterToolFailedExceptionTest {
    @Test
    fun `the message names the command, where it ran, the status and the stderr, and stdout only when there is any`() {
        val quiet = ClusterToolFailedException(ClusterTool.KUBECTL, listOf("get", "nosuchkind"), 1, "control0 (54.1.2.3)", "no such kind\n")
        val loud = ClusterToolFailedException(ClusterTool.HELM, listOf("list"), 1, "control0 (54.1.2.3)", "boom", stdout = "partial\n")

        assertThat(quiet.message).isEqualTo(
            "kubectl get nosuchkind failed on control0 (54.1.2.3) with exit status 1\nstderr:\nno such kind",
        )
        assertThat(loud.message).endsWith("stderr:\nboom\nstdout:\npartial")
    }

    @Test
    fun `a failure the transport reported no status for says so instead of inventing one`() {
        val failure = ClusterToolFailedException(ClusterTool.HELM, listOf("list"), null, "control0 (54.1.2.3)", "")

        assertThat(failure.message).isEqualTo("helm list failed on control0 (54.1.2.3) with no exit status reported")
    }

    @Test
    fun `credentials in the command never reach the message`() {
        val failure =
            ClusterToolFailedException(
                tool = ClusterTool.KUBECTL,
                args = listOf("apply", "-f", "https://alice:ghp_secrettoken@example.com/m.yaml"),
                exitStatus = 1,
                location = "control0 (54.1.2.3)",
                stderr = "",
            )

        assertThat(failure.message).doesNotContain("ghp_secrettoken").contains("https://***@example.com/m.yaml")
    }
}
