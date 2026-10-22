package com.rustyrazorblade.easydblab.localstack

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.amazon.awssdk.profiles.ProfileFile
import java.io.File

/**
 * Whether an AWS profile signs in through IAM Identity Center decides the hint a refused store check
 * gives: `aws sso login --profile <p>` helps only a profile that has an SSO session or start URL in
 * its config. A profile with static keys, or one that is not in the files at all, gets no SSO hint.
 */
class ProfileFileLoginKindTest {
    @TempDir
    lateinit var home: File

    private fun kind(
        config: String,
        credentials: String = "",
    ): AwsLoginKind {
        val configFile = File(home, "config").apply { writeText(config.trimIndent()) }
        val credentialsFile = File(home, "credentials").apply { writeText(credentials.trimIndent()) }
        return ProfileFileLoginKind {
            ProfileFile
                .aggregator()
                .addFile(
                    ProfileFile
                        .builder()
                        .content(configFile.toPath())
                        .type(ProfileFile.Type.CONFIGURATION)
                        .build(),
                ).addFile(
                    ProfileFile
                        .builder()
                        .content(credentialsFile.toPath())
                        .type(ProfileFile.Type.CREDENTIALS)
                        .build(),
                ).build()
        }
    }

    @Test
    fun `a profile with an SSO session is an SSO profile`() {
        val kind =
            kind(
                """
                [profile lab]
                sso_session = corp
                sso_account_id = 111111111111
                sso_role_name = Admin
                region = us-west-2

                [sso-session corp]
                sso_start_url = https://example.awsapps.com/start
                sso_region = us-west-2
                """,
            )

        assertThat(kind.usesSso("lab")).isTrue()
    }

    @Test
    fun `a legacy SSO profile with a start URL is an SSO profile`() {
        val kind =
            kind(
                """
                [profile old]
                sso_start_url = https://example.awsapps.com/start
                sso_region = us-west-2
                sso_account_id = 111111111111
                sso_role_name = Admin
                """,
            )

        assertThat(kind.usesSso("old")).isTrue()
    }

    @Test
    fun `a profile with static keys is not an SSO profile`() {
        val kind =
            kind(
                config =
                    """
                    [profile keys]
                    region = us-west-2
                    """,
                credentials =
                    """
                    [keys]
                    aws_access_key_id = AKIAEXAMPLE
                    aws_secret_access_key = secret
                    """,
            )

        assertThat(kind.usesSso("keys")).isFalse()
    }

    @Test
    fun `a profile that is in no file is not an SSO profile`() {
        assertThat(kind("[profile other]\nsso_session = corp").usesSso("missing")).isFalse()
    }
}
