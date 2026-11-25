package com.rustyrazorblade.easydblab.commands.profile

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.profile.Destination
import com.rustyrazorblade.easydblab.configuration.profile.Profile
import com.rustyrazorblade.easydblab.configuration.profile.ProfileStore
import com.rustyrazorblade.easydblab.configuration.profile.ProfileUnreadableException
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsCredentials
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsDestination
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * The three states the active profile can be in.
 *
 * A sum type rather than a nullable [Profile], because "absent" and "present but undecodable"
 * produce different reports and a `Profile?` cannot tell them apart.
 */
sealed interface ProfileSettings {
    /** The profile file exists and decoded into a [Profile]. */
    data class Loaded(
        val profile: Profile,
    ) : ProfileSettings

    /** No profile is saved: the profile has never been set up. */
    data object Missing : ProfileSettings

    /** A profile file exists but could not be read or decoded; [path] is the file that failed. */
    data class Unreadable(
        val path: String,
    ) : ProfileSettings
}

/**
 * Reports the active profile: its name, its directory, its file `profile.yaml`, its destination and
 * the settings it holds.
 *
 * When only the `settings.yaml` of an earlier release exists, the store's load converts it into
 * `profile.yaml`, as any command would, and leaves `settings.yaml` as it was.
 *
 * Carries no requirement annotation on purpose. `CommandExecutor.checkRequirements()` responds to
 * `@RequireProfileSetup` by running profile setup and exiting, which would replace this command's
 * report with an interactive prompt — the opposite of what it exists to do. Adding one "for
 * consistency" breaks the command.
 *
 * It also never touches `clusterState`. `PicoBaseCommand` exposes that as `by lazy`, so leaving it
 * unnamed is what lets the command run in any working directory, with no cluster workspace present.
 *
 * Uses `println()` directly: this is a read-only display command, so nothing happened that an
 * external system would need to hear about.
 */
@Command(
    name = "show",
    description = ["Show the active profile's name, directory, and settings"],
    mixinStandardHelpOptions = true,
)
class ProfileShow : PicoBaseCommand() {
    private val profileStore: ProfileStore by inject()

    override fun execute() {
        val settings =
            if (!profileStore.isSetup()) {
                ProfileSettings.Missing
            } else {
                try {
                    ProfileSettings.Loaded(profileStore.load())
                } catch (e: ProfileUnreadableException) {
                    log.warn { "Could not read the profile ${context.profile}: ${e::class.simpleName}" }
                    ProfileSettings.Unreadable(e.file.absolutePath)
                }
            }

        println(buildReport(context.profile, context.profileDir.absolutePath, settings))
    }

    companion object {
        private val log = KotlinLogging.logger {}

        private const val SETUP_HINT = "Run: easy-db-lab profile setup"

        /**
         * Renders the whole report as one string.
         *
         * Deliberately does not call any masking helper. `SetupProfile.maskValue()` renders a
         * secret as its first character followed by asterisks, which is still a leak; credentials
         * are reported only by their form, and the feature blocks only as `ENABLED` / `DISABLED`.
         */
        fun buildReport(
            profileName: String,
            profileDir: String,
            settings: ProfileSettings,
        ): String {
            val header =
                """
                |Profile:   $profileName
                |Directory: $profileDir
                |File:      $profileDir/${Constants.ConfigPaths.PROFILE_FILE}
                """.trimMargin()

            val body =
                when (settings) {
                    is ProfileSettings.Loaded -> settingsBlock(settings.profile)
                    ProfileSettings.Missing ->
                        """
                        |This profile is not configured.
                        |$SETUP_HINT
                        """.trimMargin()
                    is ProfileSettings.Unreadable ->
                        """
                        |This profile is configured but could not be read:
                        |  ${settings.path}
                        |$SETUP_HINT
                        """.trimMargin()
                }

            return "$header\n\n$body"
        }

        private fun settingsBlock(profile: Profile): String =
            """
            |  email          ${orNotSet(profile.email)}
            |  destination    ${profile.destination.displayName}
            |${destinationBlock(profile.destination)}
            |
            |  AxonOps        ${flag(profile.isAxonOpsEnabled)}
            |  Tailscale      ${flag(profile.isTailscaleEnabled)}
            """.trimMargin()

        private fun destinationBlock(destination: Destination): String =
            when (destination) {
                is AwsDestination ->
                    """
                    |  region         ${orNotSet(destination.location.region)}
                    |  credentials    ${credentialsForm(destination.credentials)}
                    |  keyName        ${orNotSet(destination.keyName)}
                    |  accountBucket  ${orNotSet(destination.accountBucket)}
                    |  sshTransport   ${destination.sshTransport.configValue}
                    """.trimMargin()
                else -> "  type           ${destination.kind}"
            }

        private fun credentialsForm(credentials: AwsCredentials): String =
            when (credentials) {
                is AwsCredentials.AwsProfile -> "${AwsCredentials.AwsProfile.TYPE} ${orNotSet(credentials.name)}"
                is AwsCredentials.AccessKey -> AwsCredentials.AccessKey.TYPE
            }

        private fun flag(enabled: Boolean): String = if (enabled) "ENABLED" else "DISABLED"

        private fun orNotSet(value: String): String = value.ifBlank { "(not set)" }
    }
}
