package com.rustyrazorblade.easydblab.commands.profile

import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.configuration.profile.AxonOps
import com.rustyrazorblade.easydblab.configuration.profile.Profile
import com.rustyrazorblade.easydblab.configuration.profile.Tailscale
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsCredentials
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsDestination
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsLocation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Tests the report text `profile show` produces.
 *
 * `buildReport` is a pure function, so these tests assert on the returned string rather than
 * capturing stdout. What the report text cannot prove — the picocli tree, the command's
 * annotations, which collaborators the lifecycle touched — lives in [ProfileCommandGroupTest].
 */
class ProfileShowTest {
    companion object {
        private const val PROFILE_NAME = "default"
        private const val PROFILE_DIR = "/home/tester/.easy-db-lab/profiles/default"

        // Sentinels chosen so they cannot appear in the report by coincidence.
        private const val ACCESS_KEY = "SENTINELACCESSKEYVALUE"
        private const val SECRET = "SENTINELSECRETVALUE"
        private const val AXONOPS_KEY = "SENTINELAXONOPSKEYVALUE"
        private const val TAILSCALE_ID = "SENTINELTAILSCALEIDVALUE"
        private const val TAILSCALE_SECRET = "SENTINELTAILSCALESECRETVALUE"
        private val SECRETS = listOf(ACCESS_KEY, SECRET, AXONOPS_KEY, TAILSCALE_ID, TAILSCALE_SECRET)

        private val NAMED = AwsCredentials.AwsProfile("tester-aws-profile")
        private val KEYS = AwsCredentials.AccessKey(ACCESS_KEY, SECRET)

        private fun profile(
            credentials: AwsCredentials = NAMED,
            axonOps: AxonOps? = AxonOps("tester-org", AXONOPS_KEY),
            tailscale: Tailscale? = Tailscale(TAILSCALE_ID, TAILSCALE_SECRET),
            sshTransport: SshTransport = SshTransport.Direct,
        ) = Profile(
            email = "tester@example.com",
            destination =
                AwsDestination(
                    location = AwsLocation("eu-central-1"),
                    credentials = credentials,
                    keyName = "tester-keypair",
                    accountBucket = "tester-account-bucket",
                    sshTransport = sshTransport,
                ),
            tailscale = tailscale,
            axonOps = axonOps,
        )

        private fun reportFor(profile: Profile) = ProfileShow.buildReport(PROFILE_NAME, PROFILE_DIR, ProfileSettings.Loaded(profile))

        @JvmStatic
        fun credentialForms(): Stream<Arguments> =
            Stream.of(
                Arguments.of(NAMED, "credentials\\s+aws-profile tester-aws-profile"),
                Arguments.of(KEYS, "credentials\\s+access-key\\s*\n"),
            )

        /** Each row: the blocks, then whether AxonOps and Tailscale read ENABLED. */
        @JvmStatic
        fun featureBlocks(): Stream<Arguments> =
            Stream.of(
                Arguments.of("both blocks complete", AxonOps("org", AXONOPS_KEY), Tailscale(TAILSCALE_ID, TAILSCALE_SECRET), true, true),
                Arguments.of("no blocks", null, null, false, false),
                Arguments.of("AxonOps key blank", AxonOps("org", ""), Tailscale(TAILSCALE_ID, TAILSCALE_SECRET), false, true),
                Arguments.of("AxonOps org blank", AxonOps("", AXONOPS_KEY), null, false, false),
                Arguments.of("AxonOps key whitespace", AxonOps("org", "   "), null, false, false),
                Arguments.of("Tailscale id empty", null, Tailscale("", TAILSCALE_SECRET), false, false),
                Arguments.of("Tailscale secret whitespace", null, Tailscale(TAILSCALE_ID, "   "), false, false),
            )

        private fun flag(enabled: Boolean) = if (enabled) "ENABLED" else "DISABLED"
    }

    /** Control: leave the destination line out of the report; the `destination` pattern fails. */
    @Test
    fun `report names the profile, its directory, its file, the destination and every visible setting`() {
        val report = reportFor(profile())

        assertThat(report)
            .contains(PROFILE_NAME)
            .contains("$PROFILE_DIR/profile.yaml")
            .containsPattern("email\\s+tester@example.com")
            .containsPattern("destination\\s+AWS")
            .containsPattern("region\\s+eu-central-1")
            .containsPattern("keyName\\s+tester-keypair")
            .containsPattern("accountBucket\\s+tester-account-bucket")
            .containsPattern("sshTransport\\s+direct")
    }

    /** Control: print the access key secret masked by SetupProfile.maskValue; the `****` check fails. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("credentialForms")
    fun `credentials show their form and never a secret`(
        credentials: AwsCredentials,
        expectedLine: String,
    ) {
        val report = reportFor(profile(credentials = credentials))

        assertThat(report).containsPattern(expectedLine)
        SECRETS.forEach { assertThat(report).doesNotContain(it) }
        // SetupProfile.maskValue renders "S****"; a report that reused it would leak a fragment.
        assertThat(report).doesNotContain("****")
    }

    /** Control: show a feature enabled when its block exists; the half-set rows fail. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("featureBlocks")
    fun `feature flags follow the block rules`(
        description: String,
        axonOps: AxonOps?,
        tailscale: Tailscale?,
        axonOpsEnabled: Boolean,
        tailscaleEnabled: Boolean,
    ) {
        val report = reportFor(profile(axonOps = axonOps, tailscale = tailscale))

        assertThat(report)
            .describedAs(description)
            .containsPattern("AxonOps\\s+${flag(axonOpsEnabled)}")
            .containsPattern("Tailscale\\s+${flag(tailscaleEnabled)}")
    }

    /** Control: print the transport's enum name; the line reads `Ssm` and the pattern fails. */
    @Test
    fun `SSH transport reads ssm once the profile selects it`() {
        assertThat(reportFor(profile(sshTransport = SshTransport.Ssm))).containsPattern("sshTransport\\s+ssm")
    }

    /** Control: drop the setup hint from the missing report; the test fails. */
    @Test
    fun `a missing profile reports it as not configured and names profile setup`() {
        val report = ProfileShow.buildReport(PROFILE_NAME, PROFILE_DIR, ProfileSettings.Missing)

        assertThat(report)
            .contains(PROFILE_NAME)
            .contains(PROFILE_DIR)
            .contains("not configured")
            .contains("easy-db-lab profile setup")
    }

    /** Control: name settings.yaml in the unreadable report; the path check fails. */
    @Test
    fun `an unreadable profile names profile yaml and profile setup`() {
        val report = ProfileShow.buildReport(PROFILE_NAME, PROFILE_DIR, ProfileSettings.Unreadable)

        assertThat(report)
            .contains("could not be read:\n  $PROFILE_DIR/profile.yaml")
            .contains("easy-db-lab profile setup")
    }
}
