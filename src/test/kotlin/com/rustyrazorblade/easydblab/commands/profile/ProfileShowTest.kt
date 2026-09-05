package com.rustyrazorblade.easydblab.commands.profile

import com.rustyrazorblade.easydblab.configuration.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests the report text `profile show` produces.
 *
 * `buildReport` is a pure function, so these tests assert on the returned string rather than
 * capturing stdout. What the report text cannot prove — the picocli tree, the command's
 * annotations, which collaborators the lifecycle touched — lives in [ProfileCommandGroupTest].
 */
class ProfileShowTest {
    private companion object {
        const val PROFILE_NAME = "default"
        const val PROFILE_DIR = "/home/tester/.easy-db-lab/profiles/default"

        // Sentinels chosen so they cannot appear in the report by coincidence.
        const val ACCESS_KEY = "SENTINELACCESSKEYVALUE"
        const val SECRET = "SENTINELSECRETVALUE"
        const val AXONOPS_KEY = "SENTINELAXONOPSKEYVALUE"
        const val TAILSCALE_ID = "SENTINELTAILSCALEIDVALUE"
        const val TAILSCALE_SECRET = "SENTINELTAILSCALESECRETVALUE"
    }

    private fun user(
        axonOpsOrg: String = "tester-org",
        axonOpsKey: String = AXONOPS_KEY,
        tailscaleClientId: String = TAILSCALE_ID,
        tailscaleClientSecret: String = TAILSCALE_SECRET,
    ) = User(
        email = "tester@example.com",
        region = "eu-central-1",
        keyName = "tester-keypair",
        awsProfile = "tester-aws-profile",
        awsAccessKey = ACCESS_KEY,
        awsSecret = SECRET,
        axonOpsOrg = axonOpsOrg,
        axonOpsKey = axonOpsKey,
        tailscaleClientId = tailscaleClientId,
        tailscaleClientSecret = tailscaleClientSecret,
        s3Bucket = "easy-db-lab-tester-bucket",
    )

    private fun reportFor(user: User) = ProfileShow.buildReport(PROFILE_NAME, PROFILE_DIR, ProfileSettings.Loaded(user))

    @Test
    fun `report names the profile, its directory, and all five visible settings`() {
        val report = reportFor(user())

        assertThat(report)
            .contains(PROFILE_NAME)
            .contains(PROFILE_DIR)
            .contains("tester@example.com")
            .contains("eu-central-1")
            .contains("tester-keypair")
            .contains("tester-aws-profile")
            .contains("easy-db-lab-tester-bucket")
    }

    @Test
    fun `report contains none of the five secret values`() {
        val report = reportFor(user())

        assertThat(report)
            .doesNotContain(ACCESS_KEY)
            .doesNotContain(SECRET)
            .doesNotContain(AXONOPS_KEY)
            .doesNotContain(TAILSCALE_ID)
            .doesNotContain(TAILSCALE_SECRET)
    }

    @Test
    fun `a masking helper would leak the first character, so no leading fragment appears either`() {
        // SetupProfile.maskValue renders "S****" for every one of these. If buildReport ever
        // reuses it the sentinel assertions above still pass, so guard the leading fragment too.
        val report = reportFor(user())

        assertThat(report).doesNotContain("****")
    }

    @Test
    fun `AxonOps is ENABLED when both the org and the key are present`() {
        // Anchored to the AxonOps line: the same fixture renders "Tailscale ENABLED", so two
        // independent contains() calls would pass even if this line read DISABLED.
        assertThat(reportFor(user())).containsPattern("AxonOps\\s+ENABLED")
    }

    @Test
    fun `AxonOps is DISABLED when no key is present`() {
        val report = reportFor(user(axonOpsKey = ""))

        assertThat(report).containsPattern("AxonOps\\s+DISABLED")
    }

    @Test
    fun `AxonOps is DISABLED when the key is set but the org is blank`() {
        // SetupProfile prompts for the org and the key as independent skippable fields, so this
        // state is reachable. Up and cassandra Start both skip AxonOps in it.
        val report = reportFor(user(axonOpsOrg = "", axonOpsKey = AXONOPS_KEY))

        assertThat(report).containsPattern("AxonOps\\s+DISABLED")
    }

    @Test
    fun `AxonOps is DISABLED when the key is whitespace rather than empty`() {
        // isAxonOpsEnabled() uses isNotBlank(), so whitespace must not read as configured.
        val report = reportFor(user(axonOpsKey = "   "))

        assertThat(report).containsPattern("AxonOps\\s+DISABLED")
    }

    @Test
    fun `Tailscale is ENABLED when both credentials are present`() {
        val report = reportFor(user())

        assertThat(report).containsPattern("Tailscale\\s+ENABLED")
    }

    @Test
    fun `Tailscale is DISABLED when the client id is empty`() {
        val report = reportFor(user(tailscaleClientId = ""))

        assertThat(report).containsPattern("Tailscale\\s+DISABLED")
    }

    @Test
    fun `Tailscale is DISABLED when the client secret is blank rather than empty`() {
        // isTailscaleEnabled() uses isNotBlank(), so whitespace must not read as configured.
        val report = reportFor(user(tailscaleClientSecret = "   "))

        assertThat(report).containsPattern("Tailscale\\s+DISABLED")
    }

    @Test
    fun `a missing settings file reports the profile as not configured and names profile setup`() {
        val report = ProfileShow.buildReport(PROFILE_NAME, PROFILE_DIR, ProfileSettings.Missing)

        assertThat(report)
            .contains(PROFILE_NAME)
            .contains(PROFILE_DIR)
            .contains("not configured")
            .contains("easy-db-lab profile setup")
    }

    @Test
    fun `an unreadable settings file names the file path and profile setup`() {
        val report = ProfileShow.buildReport(PROFILE_NAME, PROFILE_DIR, ProfileSettings.Unreadable)

        assertThat(report)
            .contains("$PROFILE_DIR/settings.yaml")
            .contains("could not be read")
            .contains("easy-db-lab profile setup")
    }
}
