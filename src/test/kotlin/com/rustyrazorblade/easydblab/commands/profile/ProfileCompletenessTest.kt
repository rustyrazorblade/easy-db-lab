package com.rustyrazorblade.easydblab.commands.profile

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Decides whether `profile setup` takes the update path (nothing left to create) or the full
 * setup path (the key pair, IAM roles, bucket and Packer VPC may be missing). The full table is
 * the contract: a profile counts as set up only when it can authenticate AND holds the AWS
 * resources every cluster needs.
 */
class ProfileCompletenessTest {
    companion object {
        private fun profile(
            email: String? = "me@example.com",
            region: String? = "us-west-2",
            awsProfile: String? = "my-profile",
            accessKey: String? = null,
            secret: String? = null,
            keyName: String? = "easy-db-lab-key",
            s3Bucket: String? = "easy-db-lab-bucket",
        ): Map<String, Any> =
            listOf(
                "email" to email,
                "region" to region,
                "awsProfile" to awsProfile,
                "awsAccessKey" to accessKey,
                "awsSecret" to secret,
                "keyName" to keyName,
                "s3Bucket" to s3Bucket,
            ).mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

        @JvmStatic
        fun cases(): Stream<Arguments> =
            Stream.of(
                Arguments.of("complete, named AWS profile", profile(), true),
                Arguments.of("complete, static credentials", profile(awsProfile = "", accessKey = "AKIA", secret = "s"), true),
                Arguments.of("blank keyName", profile(keyName = ""), false),
                Arguments.of("missing keyName", profile(keyName = null), false),
                Arguments.of("blank s3Bucket", profile(s3Bucket = ""), false),
                Arguments.of("missing s3Bucket", profile(s3Bucket = null), false),
                Arguments.of("blank keyName and s3Bucket", profile(keyName = "", s3Bucket = ""), false),
                Arguments.of("no email", profile(email = null), false),
                Arguments.of("no region", profile(region = null), false),
                Arguments.of("no way to authenticate", profile(awsProfile = ""), false),
                Arguments.of("empty profile", emptyMap<String, Any>(), false),
            )
    }

    @ParameterizedTest(name = "{0} -> set up: {2}")
    @MethodSource("cases")
    fun `a profile counts as set up only with credentials, a key pair and a bucket`(
        description: String,
        config: Map<String, Any>,
        expected: Boolean,
    ) {
        assertThat(SetupProfile.isFullySetUp(config)).describedAs(description).isEqualTo(expected)
    }
}
