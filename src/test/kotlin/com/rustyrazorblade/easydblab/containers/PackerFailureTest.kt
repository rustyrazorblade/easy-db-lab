package com.rustyrazorblade.easydblab.containers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Tests for [PackerFailure.fromLog]: which failed builds lost their SSH session to the builder (and
 * may be run again) and which failed for any other reason (and must not be). The log lines are the
 * ones Packer 1.16 and its amazon plugin write, taken from real `ssm` builds.
 */
class PackerFailureTest {
    companion object {
        private const val ESC = "\u001B"

        private fun errored(reason: String) =
            "2026/11/21 04:38:46 ui error: $ESC[1;31mBuild 'amazon-ebs.ubuntu' errored after 3 minutes 39 seconds: $reason$ESC[0m"

        @JvmStatic
        fun failures(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "a shell script whose session ended",
                    errored(
                        "Script disconnected unexpectedly. If you expected your script to disconnect, i.e. from a restart, " +
                            "you can try adding `\"expect_disconnect\": true` or `\"valid_exit_codes\": [0, 2300218]` " +
                            "to the shell provisioner parameters.",
                    ),
                    true,
                ),
                Arguments.of("the temporary script's removal", errored("Disconnect while removing temporary script."), true),
                Arguments.of(
                    "an upload cut by the read/write timeout",
                    errored("Upload failed: read tcp 127.0.0.1:8123: i/o timeout"),
                    true,
                ),
                Arguments.of("an upload whose stream ended", errored("Error uploading script: unexpected EOF"), true),
                Arguments.of("a bare EOF", errored("EOF"), true),
                Arguments.of("a reset connection", errored("Upload failed: write tcp: connection reset by peer"), true),
                Arguments.of("a broken pipe", errored("Error chmodding script file to 0755 in remote machine: write: broken pipe"), true),
                Arguments.of("a closed connection", errored("Upload failed: use of closed network connection"), true),
                Arguments.of(
                    "a script that exited non-zero",
                    errored("Script exited with non-zero exit status: 1. Allowed exit codes are: [0]"),
                    false,
                ),
                Arguments.of("an SSH that never connected", errored("Timeout waiting for SSH."), false),
                Arguments.of(
                    "an AWS error that mentions no stream",
                    errored("Error launching source instance: UnauthorizedOperation: You are not authorized"),
                    false,
                ),
                Arguments.of("a missing source file", errored("Bad source 'install/missing.sh': stat: no such file or directory"), false),
                Arguments.of(
                    "an AWS call cut by a network timeout",
                    errored(
                        "Error launching source instance: RequestError: send request failed caused by: " +
                            "Post \"https://ec2.us-west-2.amazonaws.com/\": dial tcp 10.0.0.1:443: i/o timeout",
                    ),
                    false,
                ),
                Arguments.of(
                    "an AWS call whose connection was reset",
                    errored("error creating AMI: RequestError: send request failed caused by: read: connection reset by peer"),
                    false,
                ),
                Arguments.of(
                    "an AWS SDK v2 call whose stream ended",
                    errored("Error waiting for AMI: operation error EC2: DescribeImages, https response error: unexpected EOF"),
                    false,
                ),
            )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    fun `only a lost session is a session drop`(
        case: String,
        line: String,
        dropped: Boolean,
    ) {
        val log =
            listOf(
                "2026/11/21 04:35:07 [INFO] Packer version: 1.16.1",
                line,
                "2026/11/21 04:38:46 machine readable: error-count []string{\"1\"}",
            ).joinToString("\n", postfix = "\n")

        val failure = PackerFailure.fromLog(log)

        assertThat(failure is PackerFailure.SessionDropped).`as`("$case: $failure").isEqualTo(dropped)
    }

    @Test
    fun `the reason is the build's error without the terminal colours`() {
        val failure = PackerFailure.fromLog(errored("Disconnect while removing temporary script.") + "\n")

        assertThat(failure.reason).isEqualTo("Disconnect while removing temporary script.")
    }

    @Test
    fun `a stream word in an earlier line does not make a script failure a session drop`() {
        val log =
            """
            2026/11/21 04:36:00 ui: ==> amazon-ebs.ubuntu: curl: (56) Recv failure: Connection reset by peer
            2026/11/21 04:36:01 [ERROR] Remote command exited with '1': /tmp/script.sh
            ${errored("Script exited with non-zero exit status: 1. Allowed exit codes are: [0]")}
            """.trimIndent()

        assertThat(PackerFailure.fromLog(log)).isInstanceOf(PackerFailure.BuildError::class.java)
    }

    @Test
    fun `a log with no build error is a build error that says so`() {
        val failure = PackerFailure.fromLog("2026/11/21 04:35:07 [INFO] Packer version: 1.16.1\n")

        assertThat(failure).isInstanceOf(PackerFailure.BuildError::class.java)
        assertThat(failure.reason).isEqualTo(PackerFailure.NO_ERROR_RECORDED)
    }
}
