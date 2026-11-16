package com.rustyrazorblade.easydblab.providers.ssm

import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.providers.aws.AWSCredentialsManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import java.io.File

/**
 * Tests for [SsmSessionCommandBuilder] and [SsmCliCredentials]: the command lines both SSM SSH
 * paths run, and the choice of AWS identity behind them.
 */
internal class SsmSessionCommandTest {
    @TempDir
    lateinit var profileDir: File

    private val namedProfile = SsmSessionCommandBuilder("eu-west-1", { SsmCliCredentials.NamedProfile("lab") })

    @Test
    fun `ssh session with a named profile passes the profile and no extra environment`() {
        val command = namedProfile.sshSession("i-0abc")

        assertThat(command.argv).containsExactly(
            "aws",
            "ssm",
            "start-session",
            "--target",
            "i-0abc",
            "--document-name",
            "AWS-StartSSHSession",
            "--parameters",
            "portNumber=%p",
            "--region",
            "eu-west-1",
            "--profile",
            "lab",
        )
        assertThat(command.environment).isEmpty()
    }

    @Test
    fun `static credentials point the CLI at the credentials file instead of exposing the keys`() {
        val builder =
            SsmSessionCommandBuilder("us-east-2", { SsmCliCredentials.CredentialsFile("/profiles/lab/awscredentials") })

        val command = builder.sshSession("i-0abc")

        assertThat(command.argv).endsWith("--profile", "default")
        assertThat(command.environment).containsEntry("AWS_SHARED_CREDENTIALS_FILE", "/profiles/lab/awscredentials")
    }

    /**
     * The operator's own `~/.aws/config` may give `[default]` an SSO session, a `role_arn` or a
     * `credential_process`, and the CLI would sign as that identity instead of the static keys.
     */
    @Test
    fun `static credentials keep the operator's AWS config file out of the session`() {
        val builder =
            SsmSessionCommandBuilder("us-east-2", { SsmCliCredentials.CredentialsFile("/profiles/lab/awscredentials") })

        val command = builder.portForwardSession("i-0abc", remotePort = 22, localPort = 40123)

        assertThat(command.environment).containsEntry("AWS_CONFIG_FILE", "/dev/null")
        assertThat(command.toShellCommand()).startsWith("env AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=")
    }

    @Test
    fun `port forward session uses the port forwarding document with both ports`() {
        val command = namedProfile.portForwardSession("i-0abc", remotePort = 22, localPort = 40123)

        assertThat(command.argv)
            .containsSequence("--document-name", "AWS-StartPortForwardingSession")
            .containsSequence("--parameters", "portNumber=22,localPortNumber=40123")
    }

    @Test
    fun `shell rendering quotes unsafe values but leaves the ssh port token intact`() {
        val builder =
            SsmSessionCommandBuilder("us-west-2", { SsmCliCredentials.CredentialsFile("/Users/a b/awscredentials") })

        val rendered = builder.sshSession("i-0abc").toShellCommand()

        assertThat(rendered).isEqualTo(
            "env AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE='/Users/a b/awscredentials' " +
                "aws ssm start-session --target i-0abc --document-name AWS-StartSSHSession " +
                "--parameters portNumber=%p --region us-west-2 --profile default",
        )
    }

    @Test
    fun `credentials are resolved once and only when a command is first built`() {
        var resolutions = 0
        val builder =
            SsmSessionCommandBuilder("us-west-2", {
                resolutions++
                SsmCliCredentials.NamedProfile("lab")
            })

        assertThat(resolutions).isZero()
        builder.sshSession("i-1")
        builder.portForwardSession("i-2", 22, 40000)
        assertThat(resolutions).isEqualTo(1)
    }

    @Test
    fun `a profile with a named AWS profile uses it and never writes the credentials file`() {
        val credentials = SsmCliCredentials.forUser(user(awsProfile = "sso-lab"), credentialsManager())

        assertThat(credentials).isEqualTo(SsmCliCredentials.NamedProfile("sso-lab"))
        assertThat(profileDir.listFiles()).isEmpty()
    }

    @Test
    fun `a profile with static keys uses the credentials file it writes under the default profile`() {
        val credentials = SsmCliCredentials.forUser(user(awsProfile = ""), credentialsManager())

        val file = File(profileDir, "awscredentials")
        assertThat(credentials).isEqualTo(SsmCliCredentials.CredentialsFile(file.absolutePath))
        assertThat(file.readLines()).contains("[default]", "aws_access_key_id=AKIA")
    }

    private fun credentialsManager() =
        AWSCredentialsManager(profileDir, StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIA", "secret")))

    private fun user(awsProfile: String) =
        User(
            email = "test@example.com",
            region = "us-west-2",
            keyName = "key",
            awsProfile = awsProfile,
            awsAccessKey = "AKIA",
            awsSecret = "secret",
        )
}
