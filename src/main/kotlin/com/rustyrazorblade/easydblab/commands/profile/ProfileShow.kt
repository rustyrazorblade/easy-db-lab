package com.rustyrazorblade.easydblab.commands.profile

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * The three states the active profile's `settings.yaml` can be in.
 *
 * A sum type rather than a nullable [User], because "absent" and "present but undeserializable"
 * produce different reports and a `User?` cannot tell them apart. [User] has six constructor
 * parameters with no defaults, so a hand-edited or truncated file throws during deserialization
 * instead of yielding a partial object.
 */
sealed interface ProfileSettings {
    /** `settings.yaml` exists and deserialized into a [User]. */
    data class Loaded(
        val user: User,
    ) : ProfileSettings

    /** `settings.yaml` does not exist: the profile has never been set up. */
    data object Missing : ProfileSettings

    /** `settings.yaml` exists but could not be deserialized. */
    data object Unreadable : ProfileSettings
}

/**
 * Reports the active profile: its name, its directory, and the settings it holds.
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
    private val userConfigProvider: UserConfigProvider by inject()

    @Suppress("TooGenericExceptionCaught")
    override fun execute() {
        val settings =
            if (!userConfigProvider.isSetup()) {
                ProfileSettings.Missing
            } else {
                try {
                    ProfileSettings.Loaded(userConfigProvider.getUserConfig())
                } catch (e: Exception) {
                    log.warn { "Could not deserialize settings for profile ${context.profile}: ${e::class.simpleName}" }
                    ProfileSettings.Unreadable
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
         * secret as its first character followed by asterisks, which is still a leak; secrets are
         * reported here only as `ENABLED` / `DISABLED` flags.
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
                """.trimMargin()

            val body =
                when (settings) {
                    is ProfileSettings.Loaded -> settingsBlock(settings.user)
                    ProfileSettings.Missing ->
                        """
                        |This profile is not configured.
                        |$SETUP_HINT
                        """.trimMargin()
                    ProfileSettings.Unreadable ->
                        """
                        |This profile is configured but could not be read:
                        |  $profileDir/${Constants.ConfigPaths.PROFILE_SETTINGS_FILE}
                        |$SETUP_HINT
                        """.trimMargin()
                }

            return "$header\n\n$body"
        }

        private fun settingsBlock(user: User): String =
            """
            |  email       ${orNotSet(user.email)}
            |  region      ${orNotSet(user.region)}
            |  keyName     ${orNotSet(user.keyName)}
            |  awsProfile  ${orNotSet(user.awsProfile)}
            |  s3Bucket    ${orNotSet(user.s3Bucket)}
            |
            |  AxonOps     ${flag(user.isAxonOpsEnabled())}
            |  Tailscale   ${flag(user.isTailscaleEnabled())}
            """.trimMargin()

        private fun flag(enabled: Boolean): String = if (enabled) "ENABLED" else "DISABLED"

        private fun orNotSet(value: String): String = value.ifBlank { "(not set)" }
    }
}
