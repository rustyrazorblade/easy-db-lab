package com.rustyrazorblade.easydblab.commands.profile

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Prompter
import com.rustyrazorblade.easydblab.commands.BuildImage
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.SetupProfileException
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.Policy
import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.configuration.profile.AxonOps
import com.rustyrazorblade.easydblab.configuration.profile.Profile
import com.rustyrazorblade.easydblab.configuration.profile.ProfileFileException
import com.rustyrazorblade.easydblab.configuration.profile.ProfileFileRemedy
import com.rustyrazorblade.easydblab.configuration.profile.ProfileStore
import com.rustyrazorblade.easydblab.configuration.profile.Tailscale
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsCredentials
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsDestination
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsLocation
import com.rustyrazorblade.easydblab.configuration.profile.aws.LegacyAwsProfileReader
import com.rustyrazorblade.easydblab.configuration.profile.aws.aws
import com.rustyrazorblade.easydblab.configuration.profile.aws.withAws
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.providers.aws.AWSClientFactory
import com.rustyrazorblade.easydblab.providers.aws.AWSPolicy
import com.rustyrazorblade.easydblab.providers.aws.AccountIdentity
import com.rustyrazorblade.easydblab.runCatchingException
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.ExternalIpService
import com.rustyrazorblade.easydblab.services.aws.AMIValidationException
import com.rustyrazorblade.easydblab.services.aws.AMIValidator
import com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import com.rustyrazorblade.easydblab.services.aws.AwsKeyPairService
import com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import com.rustyrazorblade.easydblab.configuration.profile.aws.LegacyAwsProfileReader.Companion as Keys

/**
 * Sets up the active profile interactively.
 *
 * A profile has exactly one destination; the only one available is AWS. A new profile states it
 * (`Destination: AWS`) without asking, and re-running setup reviews that destination only: choosing
 * another destination means another profile name.
 *
 * Setup prefills from the store's flat setup values (`ProfileStore.loadForSetup`), for the current
 * `profile.yaml` or an earlier `settings.yaml`, so a value that does not parse, such as an unknown SSH
 * transport, is reported and asked for again instead of stopping the command that fixes it.
 *
 * The setup process follows these phases:
 * 1. Check if profile already exists (review path if complete)
 * 2. Collect core credentials (email, region, AWS profile or access key/secret)
 * 3. Validate AWS credentials (retry on failure)
 * 4. Collect optional info (AxonOps, Tailscale, SSH transport)
 * 5. Ensure AWS resources exist (key pair, IAM roles, S3 bucket, VPC, AMI)
 */
@Command(
    name = "setup",
    description = ["Set up user profile interactively"],
)
class SetupProfile : PicoBaseCommand() {
    private val profileStore: ProfileStore by inject()
    private val awsResourceSetup: AWSResourceSetupService by inject()
    private val awsInfra: AwsInfrastructureService by inject()
    private val s3BucketService: AwsS3BucketService by inject()
    private val amiValidator: AMIValidator by inject()
    private val commandExecutor: CommandExecutor by inject()
    private val prompter: Prompter by inject()
    private val awsClientFactory: AWSClientFactory by inject()
    private val keyPairService: AwsKeyPairService by inject()
    private val externalIpService: ExternalIpService by inject()

    override fun execute() {
        val saved = profileStore.loadForSetup()

        if (isFullySetUp(profileFrom(saved))) {
            handleExistingProfile(saved)
            return
        }

        runFullSetup(saved)
    }

    /**
     * Reviews a complete profile's destination. Prompts for each optional field, allowing updates.
     */
    private fun handleExistingProfile(saved: Map<String, String>) {
        eventBus.emit(Event.Setup.ProfileAlreadyConfigured(context.profile))
        val reviewed = loadOrRepair(saved)
        val destination = reviewed.profile.aws

        // A profile made before the easy_db_lab marker has a bucket and key pair with only the
        // legacy one. This needs working credentials, and runs before the prompts so a failure
        // (expired credentials, a key pair that no longer exists) stops setup before any change.
        // A complete profile always names a bucket; the check only guards a profile read without one.
        if (destination.accountBucket.isNotBlank()) s3BucketService.ensureMarkerTags(destination.accountBucket)
        keyPairService.ensureMarkerTags(destination)

        // Collect optional info, allowing updates
        eventBus.emit(Event.Setup.UpdatePrompt)
        collectAndSaveOptionalInfoWithUpdate(saved, askTransport = !reviewed.transportAsked)

        eventBus.emit(Event.Setup.ConfigurationUpdated)
    }

    /**
     * The saved profile, loaded once. A saved value this version cannot decode (an unknown SSH
     * transport, a value of the wrong form) makes every load of the profile fail, and with it every AWS
     * client this command builds from the profile. Such a profile is saved again from the flat setup
     * values, so the rest of setup can use it. A bad transport is reported and asked for first.
     *
     * A destination type this version does not know never reaches the repair: [ProfileStore.loadForSetup]
     * fails on it first, because a rebuild as AWS would lose the fields only that type knows.
     */
    private fun loadOrRepair(saved: Map<String, String>): ReviewedProfile {
        val unreadable = saved[Keys.SSH_TRANSPORT]?.takeIf { SshTransport.parse(it) == null }
        if (unreadable == null) loadIfDecodes()?.let { return ReviewedProfile(it, transportAsked = false) }

        val transport =
            unreadable?.let {
                eventBus.emit(Event.Setup.InvalidSshTransport(it, SshTransport.entries.map { entry -> entry.configValue }))
                promptForSshTransport(SshTransport.Direct)
            }
        val rebuilt = profileFrom(saved) ?: throw SetupProfileException("The saved profile has no credentials")
        val repaired = transport?.let { chosen -> rebuilt.withAws { it.copy(sshTransport = chosen) } } ?: rebuilt
        profileStore.replace(repaired)
        return ReviewedProfile(repaired, transportAsked = transport != null)
    }

    /**
     * The saved profile, or null when it fails in a way setup repairs. Any other failure (a file that
     * changed under this command and no longer parses) is the user's to fix and is rethrown.
     */
    private fun loadIfDecodes(): Profile? =
        try {
            profileStore.load()
        } catch (e: ProfileFileException) {
            if (e.remedy != ProfileFileRemedy.RUN_SETUP) throw e
            null
        }

    /** The profile the review continues from, and whether the transport was already asked for, so it is not asked twice. */
    private data class ReviewedProfile(
        val profile: Profile,
        val transportAsked: Boolean,
    )

    /**
     * Runs the full setup process for new profiles.
     */
    private fun runFullSetup(saved: Map<String, String>) {
        showWelcomeMessage()
        // Only a profile that has never recorded a destination fixes one; there is no choice to offer.
        if (!profileStore.file.exists()) eventBus.emit(Event.Setup.DestinationSelected(AwsDestination.DISPLAY_NAME))

        // Retry loop for credential collection and validation
        val core = collectAndValidateCredentials(saved)

        profileStore.replace(createInitialProfile(core, saved))
        eventBus.emit(Event.Setup.CredentialsSaved)

        collectAndSaveOptionalInfo(saved)

        ensureAwsResources(core)

        showSuccessMessage()
    }

    /**
     * Collects credentials and validates them, retrying on failure.
     * Returns the validated email, region and credentials.
     * @throws SetupProfileException if credentials fail validation after max retries
     */
    private fun collectAndValidateCredentials(saved: Map<String, String>): CoreCredentials {
        var attempt = 0
        var regionRejected = false
        var credentialsRejected = false
        // Every attempt counts, a rejected region included, so setup asks at most
        // MAX_CREDENTIAL_RETRIES times.
        while (attempt < MAX_CREDENTIAL_RETRIES) {
            attempt++
            // A saved AWS profile is used until a credential check fails with it; the retry then
            // asks for the profile again instead of trying the same one. A rejected region is
            // asked for again the same way, and leaves the saved profile in use.
            var retryConfig = saved
            if (credentialsRejected) retryConfig = retryConfig - Keys.AWS_PROFILE
            if (regionRejected) retryConfig = retryConfig - Keys.REGION
            val core = collectCoreCredentials(retryConfig)
            if (!acceptRegion(core.region, lastAttempt = attempt >= MAX_CREDENTIAL_RETRIES)) {
                // Stays set: a later credentials retry must not read the rejected saved region again.
                regionRejected = true
                continue
            }

            try {
                validateAwsCredentials(core.credentials, core.region)
                return core
            } catch (e: SetupProfileException) {
                if (attempt >= MAX_CREDENTIAL_RETRIES) {
                    throw e
                }
                credentialsRejected = true
                eventBus.emit(Event.Setup.CredentialValidationRetry(attempt, MAX_CREDENTIAL_RETRIES))
                // Loop continues - will ask for profile/credentials again
            }
        }
        // This shouldn't be reached, but satisfies the compiler
        throw SetupProfileException("Maximum credential validation attempts exceeded")
    }

    /**
     * Whether [region] is one the SDK knows. An unknown region is reported, and the caller asks for
     * it again; on the [lastAttempt] it fails setup instead.
     *
     * @throws SetupProfileException for an unknown region on the last attempt
     */
    private fun acceptRegion(
        region: String,
        lastAttempt: Boolean,
    ): Boolean {
        if (AWSClientFactory.isKnownRegion(region)) return true
        eventBus.emit(Event.Setup.UnknownRegion(region))
        if (lastAttempt) throw SetupProfileException("Unknown AWS region: '$region'")
        return false
    }

    companion object {
        /** Maximum number of credential validation attempts before giving up. */
        const val MAX_CREDENTIAL_RETRIES = 3

        private val reader = LegacyAwsProfileReader()

        /**
         * The profile the flat setup [values] describe, or null when they hold no credentials. A saved
         * SSH transport that does not parse is left out, so the profile reads as using the default;
         * setup reports the bad value and asks for it.
         */
        internal fun profileFrom(values: Map<String, String>): Profile? {
            val transport = values[Keys.SSH_TRANSPORT]
            val readable = if (transport != null && SshTransport.parse(transport) == null) values - Keys.SSH_TRANSPORT else values
            return reader.migrate(readable)
        }

        /**
         * Whether [profile] has nothing left to create: an email and region, a way to authenticate
         * (which a [Profile] always has), and the key pair and bucket every cluster needs. A profile
         * missing any of these takes the full setup path, which keeps the values it has and creates
         * the rest; the review path only re-prompts optional fields and would leave it incomplete.
         */
        internal fun isFullySetUp(profile: Profile?): Boolean {
            val destination = profile?.destination as? AwsDestination ?: return false
            return profile.email.isNotBlank() &&
                destination.location.region.isNotBlank() &&
                destination.keyName.isNotBlank() &&
                destination.accountBucket.isNotBlank()
        }
    }

    /**
     * Collects core credentials required for AWS access.
     * Asks for AWS profile first; if provided, skips access key/secret prompts.
     */
    private fun collectCoreCredentials(saved: Map<String, String>): CoreCredentials {
        eventBus.emit(Event.Setup.EmailPromptInfo)
        val email = promptIfMissing(saved, Keys.EMAIL, "What's your email?", "")
        val region = promptIfMissing(saved, Keys.REGION, "What AWS region do you use?", "us-west-2")

        // A profile that already names its AWS profile is not asked again. Otherwise ask first
        // (empty = manual credentials).
        val awsProfile =
            saved[Keys.AWS_PROFILE]?.takeIf { it.isNotBlank() }
                ?: prompter.prompt(
                    "AWS Profile name (or press Enter to enter credentials manually)",
                    "",
                )

        val credentials =
            if (awsProfile.isNotEmpty()) {
                AwsCredentials.AwsProfile(awsProfile)
            } else {
                val key = promptIfMissing(saved, Keys.AWS_ACCESS_KEY, "Please enter your AWS Access Key", "")
                val secret =
                    promptIfMissing(
                        saved,
                        PromptField(Keys.AWS_SECRET, "Please enter your AWS Secret Access Key", "", secret = true),
                    )
                AwsCredentials.AccessKey(key, secret)
            }

        return CoreCredentials(email, region, credentials)
    }

    /**
     * Validates AWS credentials and optionally displays IAM policies.
     * @throws SetupProfileException if credentials are invalid
     */
    private fun validateAwsCredentials(
        credentials: AwsCredentials,
        region: String,
    ) {
        eventBus.emit(Event.Setup.ValidatingCredentials)

        // The command layer cannot name the SDK's exception types (the package-boundary test forbids
        // it), so this boundary takes any failure of the validation and reports it as one.
        runCatchingException {
            val identity = createAccountIdentity(credentials, region)
            identity.checkPermissions()

            eventBus.emit(Event.Setup.CredentialsValidSuccess)

            offerIamPolicyDisplay(identity)
        }.onFailure { e ->
            eventBus.emit(Event.Setup.CredentialsValidFailed)
            throw SetupProfileException("AWS credentials are invalid", e)
        }
    }

    /**
     * Creates an account identity for the credential form the user gave.
     */
    private fun createAccountIdentity(
        credentials: AwsCredentials,
        region: String,
    ): AccountIdentity =
        when (credentials) {
            is AwsCredentials.AwsProfile -> awsClientFactory.createAccountIdentityWithProfile(credentials.name, region)
            is AwsCredentials.AccessKey ->
                awsClientFactory.createAccountIdentity(credentials.accessKeyId, credentials.secretAccessKey, region)
        }

    /**
     * Offers to display IAM policies with the user's account ID.
     */
    private fun offerIamPolicyDisplay(identity: AccountIdentity) {
        val showPolicies =
            prompter.prompt(
                "Print the IAM policies easy-db-lab needs (to request access from an AWS admin)? " +
                    "Not required if setup completes — view anytime with 'show-iam-policies'",
                "N",
            )
        if (showPolicies.equals("y", true)) {
            val accountId = identity.getAccountId()
            displayIAMPolicies(accountId)
        }
    }

    /**
     * The profile setup first saves: the validated credentials and region, with every other value
     * the saved profile already held.
     */
    private fun createInitialProfile(
        core: CoreCredentials,
        saved: Map<String, String>,
    ): Profile {
        val value = { key: String -> saved[key].orEmpty() }
        return Profile(
            email = core.email,
            destination =
                AwsDestination(
                    location = AwsLocation(core.region),
                    credentials = core.credentials,
                    keyName = value(Keys.KEY_NAME),
                    accountBucket = value(Keys.S3_BUCKET),
                    sshTransport = existingSshTransport(saved) ?: SshTransport.Direct,
                ),
            tailscale =
                Tailscale.orNull(
                    value(Keys.TAILSCALE_CLIENT_ID),
                    value(Keys.TAILSCALE_CLIENT_SECRET),
                    saved[Keys.TAILSCALE_TAG] ?: Constants.Tailscale.DEFAULT_DEVICE_TAG,
                ),
            axonOps = AxonOps.orNull(value(Keys.AXONOPS_ORG), value(Keys.AXONOPS_KEY)),
        )
    }

    /**
     * Collects optional configuration info and saves it into the profile.
     * Used during initial setup - skips prompts for fields with no default.
     */
    private fun collectAndSaveOptionalInfo(saved: Map<String, String>) {
        val axonOpsOrg = promptIfMissing(saved, PromptField(Keys.AXONOPS_ORG, "AxonOps Org", "", skippable = true))
        val axonOpsKey =
            promptIfMissing(saved, PromptField(Keys.AXONOPS_KEY, "AxonOps Key", "", secret = true, skippable = true))

        // Collect optional Tailscale credentials
        eventBus.emit(Event.Setup.TailscaleSetupInstructions(Constants.Tailscale.DEFAULT_DEVICE_TAG))
        val tailscaleClientId =
            promptIfMissing(saved, PromptField(Keys.TAILSCALE_CLIENT_ID, "Tailscale OAuth Client ID", "", skippable = true))
        val tailscaleClientSecret =
            promptIfMissing(
                saved,
                PromptField(Keys.TAILSCALE_CLIENT_SECRET, "Tailscale OAuth Client Secret", "", secret = true, skippable = true),
            )

        val transport = existingSshTransport(saved) ?: promptForSshTransport(SshTransport.Direct)

        saveOptionalInfo(OptionalInfo(axonOpsOrg, axonOpsKey, tailscaleClientId, tailscaleClientSecret, transport))
        eventBus.emit(Event.Setup.ConfigurationSaved)
    }

    /**
     * Collects optional configuration info for the review of an existing profile.
     * Always prompts for each field, showing current value as default.
     * Empty input keeps the existing value.
     *
     * @param askTransport false when this run already asked for the transport
     */
    private fun collectAndSaveOptionalInfoWithUpdate(
        saved: Map<String, String>,
        askTransport: Boolean,
    ) {
        val value = { key: String -> saved[key].orEmpty() }

        // AxonOps settings
        eventBus.emit(Event.Setup.AxonOpsConfigHeader)
        val axonOpsOrg = promptForUpdate("AxonOps Org", value(Keys.AXONOPS_ORG))
        val axonOpsKey = promptForUpdate("AxonOps Key", value(Keys.AXONOPS_KEY), secret = true)

        // Tailscale settings
        eventBus.emit(Event.Setup.TailscaleConfigHeader(Constants.Tailscale.DEFAULT_DEVICE_TAG))
        val tailscaleClientId = promptForUpdate("Tailscale OAuth Client ID", value(Keys.TAILSCALE_CLIENT_ID))
        val tailscaleClientSecret = promptForUpdate("Tailscale OAuth Client Secret", value(Keys.TAILSCALE_CLIENT_SECRET), secret = true)

        val current = profileStore.load().aws.sshTransport
        val transport = if (askTransport) promptForSshTransport(current) else current

        saveOptionalInfo(OptionalInfo(axonOpsOrg, axonOpsKey, tailscaleClientId, tailscaleClientSecret, transport))
        eventBus.emit(Event.Setup.ConfigSectionSaved)
    }

    /** Saves the optional settings into the profile as it is saved now; a half-set pair is kept. */
    private fun saveOptionalInfo(info: OptionalInfo) {
        profileStore.update { profile ->
            profile
                .copy(
                    axonOps = AxonOps.orNull(info.axonOpsOrg, info.axonOpsKey),
                    tailscale =
                        Tailscale.orNull(
                            info.tailscaleClientId,
                            info.tailscaleClientSecret,
                            profile.tailscale?.tag ?: Constants.Tailscale.DEFAULT_DEVICE_TAG,
                        ),
                ).withAws { it.copy(sshTransport = info.sshTransport) }
        }
    }

    /**
     * Asks how SSH should reach cluster nodes, re-asking until the answer is a known transport.
     * Empty input keeps [current]. The value is not secret, so unlike [promptForUpdate] it is
     * shown unmasked.
     */
    private fun promptForSshTransport(current: SshTransport): SshTransport {
        eventBus.emit(Event.Setup.SshTransportConfigHeader)
        val choices = SshTransport.entries.map { it.configValue }
        val question = "SSH transport (${choices.joinToString(", ")})? [${current.configValue}]"

        while (true) {
            val answer = prompter.prompt(question, "")
            if (answer.isBlank()) return current
            SshTransport.parse(answer)?.let { return it }
            eventBus.emit(Event.Setup.InvalidSshTransport(answer, choices))
        }
    }

    /** The transport already saved in [saved], or null when absent or unrecognized. */
    private fun existingSshTransport(saved: Map<String, String>): SshTransport? = saved[Keys.SSH_TRANSPORT]?.let { SshTransport.parse(it) }

    /**
     * Prompts for a field update, showing masked current value.
     * Format: "Field? [A****]" where A is first char and **** masks the rest.
     * Empty values show "(not set)".
     */
    private fun promptForUpdate(
        fieldName: String,
        currentValue: String,
        secret: Boolean = false,
    ): String {
        val displayValue = maskValue(currentValue)

        val prompt = "$fieldName? [$displayValue]"
        val input = prompter.prompt(prompt, "", secret)

        // Empty input means keep current value
        return input.ifEmpty { currentValue }
    }

    /**
     * Masks a value for display, showing first character + asterisks.
     * Example: "myvalue" -> "m****"
     * Empty values return "(not set)".
     */
    private fun maskValue(value: String): String =
        when {
            value.isEmpty() -> "(not set)"
            else -> "${value[0]}****"
        }

    /**
     * Ensures all required AWS resources exist. Each step reads the profile as saved now, so a key
     * pair or bucket an earlier step saved is the one a later step, the AMI build among them, sees.
     */
    private fun ensureAwsResources(core: CoreCredentials) {
        ensureKeyPair(core)
        ensureIamRoles()
        ensureS3Bucket()
        ensurePackerVpc()
        ensureAmi(core.region)
    }

    /**
     * Generates an AWS key pair if the profile names none; the key pair service saves its name.
     */
    private fun ensureKeyPair(core: CoreCredentials) {
        val destination = profileStore.load().aws
        if (destination.keyName.isBlank()) {
            eventBus.emit(Event.Setup.GeneratingKeyPair)
            keyPairService.generate(core.region, core.credentials)
            eventBus.emit(Event.Setup.KeyPairSaved)
        } else {
            keyPairService.ensureMarkerTags(destination)
        }
    }

    /**
     * Ensures IAM roles are configured.
     */
    private fun ensureIamRoles() {
        eventBus.emit(Event.Setup.IamRolesConfiguring)
        awsResourceSetup.ensureAWSResources()
        eventBus.emit(Event.Setup.IamRolesValidated)
    }

    /**
     * Creates an S3 bucket for shared resources if one doesn't exist, and gives an existing one
     * any marker tag it lacks. The bucket service saves a new bucket's name into the profile.
     */
    private fun ensureS3Bucket() {
        s3BucketService.ensureMarkedAccountBucket()
    }

    /**
     * Creates Packer VPC infrastructure.
     */
    private fun ensurePackerVpc() {
        eventBus.emit(Event.Setup.PackerVpcCreating)
        awsInfra.ensurePackerInfrastructure(Constants.Network.SSH_PORT, externalIpService.getExternalCidr())
        eventBus.emit(Event.Setup.PackerVpcReady)
    }

    /**
     * Validates that a required AMI exists, offering to build one if not found.
     */
    private fun ensureAmi(region: String) {
        eventBus.emit(Event.Setup.CheckingAmi)
        val archType = Arch.AMD64

        try {
            amiValidator.validateAMI(overrideAMI = "", requiredArchitecture = archType)
            eventBus.emit(Event.Setup.AmiFound(archType.type))
        } catch (e: AMIValidationException.NoAMIFound) {
            handleMissingAmi(archType, region)
        }
    }

    /**
     * Handles the case when no AMI is found - prompts user and optionally builds one.
     */
    private fun handleMissingAmi(
        archType: Arch,
        region: String,
    ) {
        eventBus.emit(Event.Setup.AmiNotFound(archType.type))

        val proceed = prompter.prompt("Press Enter to start building the AMI, or type 'skip' to exit setup", "")

        if (proceed.equals("skip", ignoreCase = true)) {
            eventBus.emit(Event.Setup.AmiSkipped)
            return
        }

        buildAmi(archType, region)
    }

    /**
     * Builds an AMI for the specified architecture.
     */
    private fun buildAmi(
        archType: Arch,
        region: String,
    ) {
        runCatchingException {
            eventBus.emit(Event.Setup.AmiBuildStarting(archType.type))

            val exitCode =
                commandExecutor.execute {
                    BuildImage().apply {
                        buildArgs.arch = archType
                        buildArgs.region = region
                    }
                }

            // The executor reports a failed build by its exit code, not by throwing.
            if (exitCode == 0) {
                eventBus.emit(Event.Setup.AmiBuildSuccess)
            } else {
                eventBus.emit(Event.Setup.AmiBuildFailed("build-image exited with code $exitCode", archType.type, region))
            }
        }.onFailure { buildError ->
            eventBus.emit(Event.Setup.AmiBuildFailed(buildError.message ?: "unknown error", archType.type, region))
        }
    }

    /**
     * Shows the success message after setup completes.
     */
    private fun showSuccessMessage() {
        eventBus.emit(Event.Setup.SetupComplete)
    }

    private fun showWelcomeMessage() {
        eventBus.emit(Event.Setup.WelcomeMessage(context.profile))
    }

    /**
     * Displays IAM policies without confirmation prompt.
     * Used when user opts to see policies during setup.
     *
     * @param accountId The AWS account ID to substitute in policies
     */
    private fun displayIAMPolicies(accountId: String) {
        val policies = AWSPolicy.UserIAM.loadAll(accountId)
        eventBus.emit(Event.Setup.IamPoliciesHeader)
        displayPolicyBodies(policies)
        eventBus.emit(Event.Setup.IamPoliciesFooter)
    }

    private fun displayPolicyBodies(policies: List<Policy>) {
        policies.forEachIndexed { index, policy ->
            eventBus.emit(Event.Setup.IamPolicyDisplay(index, policy.name, policy.body))
        }
    }

    /**
     * Prompts for a field only if it's missing from existing config.
     * Returns existing value if present, prompts user otherwise.
     */
    private fun promptIfMissing(
        saved: Map<String, String>,
        field: PromptField,
    ): String {
        saved[field.fieldName]?.let { return it }

        if (field.skippable && field.default.isEmpty()) {
            return ""
        }

        return prompter.prompt(field.prompt, field.default, field.secret)
    }

    /**
     * Convenience overload for non-skippable, non-secret fields.
     */
    private fun promptIfMissing(
        saved: Map<String, String>,
        fieldName: String,
        prompt: String,
        default: String,
    ): String = promptIfMissing(saved, PromptField(fieldName, prompt, default))

    /**
     * Configuration for a field to prompt for.
     */
    private data class PromptField(
        val fieldName: String,
        val prompt: String,
        val default: String,
        val secret: Boolean = false,
        val skippable: Boolean = false,
    )

    /**
     * The core values collected during setup: who owns the profile, where in AWS it runs, and how
     * it authenticates.
     */
    private data class CoreCredentials(
        val email: String,
        val region: String,
        val credentials: AwsCredentials,
    )

    /**
     * The optional settings collected during setup. Either half of a pair may be blank; a half-set
     * pair is saved so the next run can prefill it, and stays disabled.
     */
    private data class OptionalInfo(
        val axonOpsOrg: String,
        val axonOpsKey: String,
        val tailscaleClientId: String,
        val tailscaleClientSecret: String,
        val sshTransport: SshTransport,
    )
}
