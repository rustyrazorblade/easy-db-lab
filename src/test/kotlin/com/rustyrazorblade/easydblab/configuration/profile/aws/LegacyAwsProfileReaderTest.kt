package com.rustyrazorblade.easydblab.configuration.profile.aws

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.configuration.profile.AxonOps
import com.rustyrazorblade.easydblab.configuration.profile.Profile
import com.rustyrazorblade.easydblab.configuration.profile.Tailscale
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource

/**
 * The conversion of the earlier flat profile file, over fixtures that main's Jackson writer produced
 * from `User` values, so each one is exactly what an earlier release left on disk.
 */
class LegacyAwsProfileReaderTest {
    private val reader = LegacyAwsProfileReader()

    @ParameterizedTest(name = "{0}")
    @MethodSource("migrations")
    fun `earlier file maps to the profile it describes`(
        fixture: String,
        expected: Profile?,
    ) {
        assertThat(reader.migrate(legacyFixture(fixture))).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("migrations")
    fun `an earlier file holds credentials exactly when it migrates to a profile`(
        fixture: String,
        expected: Profile?,
    ) {
        assertThat(reader.holdsCredentials(legacyFixture(fixture))).isEqualTo(expected != null)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("half-tailscale", "half-axonops", "aws-profile", "full")
    fun `a written getter key never enables a feature`(fixture: String) {
        val values = legacyFixture(fixture)
        val forged = values + mapOf("isTailscaleEnabled" to "true", "isAxonOpsEnabled" to "true")
        val profile = checkNotNull(reader.migrate(forged))
        assertThat(profile.isTailscaleEnabled).isEqualTo(fixture == "full")
        assertThat(profile.isAxonOpsEnabled).isEqualTo(fixture == "full")
    }

    @Test
    fun `an unknown ssh transport fails naming the value and the choices`() {
        assertThatThrownBy { reader.migrate(legacyFixture("bad-transport")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("'carrier-pigeon'")
            .hasMessageContaining("direct, ssm")
    }

    @Test
    fun `an earlier file with no transport key reads as direct`() {
        val values = legacyFixture("aws-profile") - "sshTransport"
        assertThat(checkNotNull(reader.migrate(values)).destination)
            .isEqualTo(AwsDestination(AwsLocation(REGION), ssoProfile, KEY, BUCKET, SshTransport.Direct))
    }

    companion object {
        private const val EMAIL = "someone@example.com"
        private const val REGION = "us-west-2"
        private const val KEY = "easy-db-lab-fixture-key"
        private const val BUCKET = "easy-db-lab-fixture-bucket"
        private val ssoProfile = AwsCredentials.AwsProfile("fixture-sso")
        private val accessKey = AwsCredentials.AccessKey("AKIAFIXTUREKEY", "fixture-secret-value")
        private val tailscale = Tailscale("fixture-ts-id", "fixture-ts-secret", "tag:fixture")
        private val axonOps = AxonOps("fixture-org", "fixture-axonops-key")

        /** The scalar values of an earlier-format fixture, keyed as the file names them. */
        fun legacyFixture(name: String): Map<String, String> {
            val resource = "/com/rustyrazorblade/easydblab/configuration/profile/legacy/$name.yaml"
            val text = checkNotNull(LegacyAwsProfileReaderTest::class.java.getResource(resource)).readText()
            val map = Yaml.default.parseToYamlNode(text) as YamlMap
            return map.entries.entries.associate { (key, value) -> key.content to (value as YamlScalar).content }
        }

        private fun profile(
            credentials: AwsCredentials,
            tailscale: Tailscale? = null,
            axonOps: AxonOps? = null,
            sshTransport: SshTransport = SshTransport.Direct,
        ) = Profile(
            email = EMAIL,
            destination = AwsDestination(AwsLocation(REGION), credentials, KEY, BUCKET, sshTransport),
            tailscale = tailscale,
            axonOps = axonOps,
        )

        @JvmStatic
        fun migrations(): List<Arguments> =
            listOf(
                Arguments.of("aws-profile", profile(ssoProfile)),
                Arguments.of("access-key", profile(accessKey)),
                Arguments.of("both", profile(ssoProfile)),
                Arguments.of("neither", null),
                Arguments.of("half-tailscale", profile(ssoProfile, tailscale = Tailscale("fixture-ts-id", "", "tag:easy-db-lab"))),
                Arguments.of("half-axonops", profile(ssoProfile, axonOps = AxonOps("fixture-org", ""))),
                Arguments.of("full", profile(ssoProfile, tailscale, axonOps, SshTransport.Ssm)),
            )
    }
}
