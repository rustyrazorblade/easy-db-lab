package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Prompter
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.Policy
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.providers.aws.AWSClientFactory
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.aws.AMIValidationException
import com.rustyrazorblade.easydblab.services.aws.AMIValidator
import com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import software.amazon.awssdk.regions.Region

/**
 * Sets up user profile interactively.
 *
 * The setup process follows these phases:
 * 1. Check if profile already exists (early exit if complete)
 * 2. Collect core credentials (email, region, AWS profile or access key/secret)
 * 3. Validate AWS credentials (retry on failure)
 * 4. Collect optional info (AxonOps)
 * 5. Ensure AWS resources exist (key pair, IAM roles, S3 bucket, VPC, AMI)
 */
@Command(
    name = "setup-profile",
    aliases = ["setup"],
    description = ["Set up user profile interactively"],
)
class SetupProfile : PicoBaseCommand() {
    private val userConfigProvider: UserConfigProvider by inject()
    private val awsResourceSetup: AWSResourceSetupService by inject()
    private val awsInfra: AwsInfrastructureService by inject()
    private val amiValidator: AMIValidator by inject()
    private val commandExecutor: CommandExecutor by inject()
    private val prompter: Prompter by inject()
    private val awsClientFactory: AWSClientFactory by inject()

    override fun execute() {
        val existingConfig = userConfigProvider.loadExistingConfig()

        if (isProfileAlreadySetUp(existingConfig)) {
            handleExistingProfile(existingConfig)
            return
        }

        runFullSetup(existingConfig)
    }

    /**
     * Handles the case when profile already exists.
     * Prompts for each optional field individually, allowing updates.
     */
    private fun handleExistingProfile(existingConfig: Map<String, Any>) {
        eventBus.emit(Event.Setup.ProfileAlreadyConfigured(context.profile))
        eventBus.emit(Event.Setup.UpdatePrompt)

        // Load existing config as User object
        val userConfig = userConfigProvider.getUserConfig()

        // Collect optional info, allowing updates
        collectAndSaveOptionalInfoWithUpdate(existingConfig, userConfig)

        eventBus.emit(Event.Setup.ConfigurationUpdated)
    }

    /**
     * Runs the full setup process for new profiles.
     */
    private fun runFullSetup(existingConfig: Map<String, Any>) {
        showWelcomeMessage()

        // Retry loop for credential collection and validation
        val (credentials, regionObj) = collectAndValidateCredentials(existingConfig)

        val userConfig = createInitialUserConfig(credentials, existingConfig)
        userConfigProvider.saveUserConfig(userConfig)
        eventBus.emit(Event.Setup.CredentialsSaved)

        collectAndSaveOptionalInfo(existingConfig, userConfig)

        ensureAwsResources(userConfig, credentials, regionObj)

        showSuccessMessage()
    }

    /**
     * Checks if the profile already has all required fields configured.
     * Accepts either profile-based auth (awsProfile set) or static credentials.
     */
    private fun isProfileAlreadySetUp(existingConfig: Map<String, Any>): Boolean {
        val hasEmailAndRegion = existingConfig.containsKey("email") && existingConfig.containsKey("region")
        if (!hasEmailAndRegion) return false

        val hasProfile = (existingConfig["awsProfile"] as? String)?.isNotEmpty() == true
        val hasCredentials = existingConfig.containsKey("awsAccessKey") && existingConfig.containsKey("awsSecret")

        return hasProfile || hasCredentials
    }

    /**
     * Collects credentials and validates them, retrying on failure.
     * Returns validated credentials and region.
     * @throws SetupProfileException if credentials fail validation after max retries
     */
    private fun collectAndValidateCredentials(existingConfig: Map<String, Any>): Pair<CoreCredentials, Region> {
        var attempt = 0
        while (attempt < MAX_CREDENTIAL_RETRIES) {
            attempt++
            val credentials = collectCoreCredentials(existingConfig)
            val regionObj = Region.of(credentials.region)

            try {
                validateAwsCredentials(credentials, regionObj)
                return credentials to regionObj
            } catch (e: SetupProfileException) {
                if (attempt >= MAX_CREDENTIAL_RETRIES) {
                    throw e
                }
                eventBus.emit(Event.Setup.CredentialValidationRetry(attempt, MAX_CREDENTIAL_RETRIES))
                // Loop continues - will ask for profile/credentials again
            }
        }
        // This shouldn't be reached, but satisfies the compiler
        throw SetupProfileException("Maximum credential validation attempts exceeded")
    }

    companion object {
        /** Maximum number of credential validation attempts before giving up. */
        const val MAX_CREDENTIAL_RETRIES = 3
    }

    /**
     * Collects core credentials required for AWS access.
     * Asks for AWS profile first; if provided, skips access key/secret prompts.
     */
    private fun collectCoreCredentials(existingConfig: Map<String, Any>): CoreCredentials {
        eventBus.emit(Event.Setup.EmailPromptInfo)
        val email = promptIfMissing(existingConfig, "email", "What's your email?", "")
        val region = promptIfMissing(existingConfig, "region", "What AWS region do you use?", "us-west-2")

        // Ask for AWS profile first (empty = manual credentials)
        val awsProfile =
            prompter.prompt(
                "AWS Profile name (or press Enter to enter credentials manually)",
                "",
            )

        val (awsAccessKey, awsSecret) =
            if (awsProfile.isNotEmpty()) {
                // Using profile - skip credential prompts
                "" to ""
            } else {
                // Manual credentials
                val key = promptIfMissing(existingConfig, "awsAccessKey", "Please enter your AWS Access Key", "")
                val secret =
                    promptIfMissing(
                        existingConfig,
                        PromptField("awsSecret", "Please enter your AWS Secret Access Key", "", secret = true),
                    )
                key to secret
            }

        return CoreCredentials(email, region, awsProfile, awsAccessKey, awsSecret)
    }

    /**
     * Validates AWS credentials and optionally displays IAM policies.
     * Uses profile-based auth if awsProfile is set, otherwise static credentials.
     * @throws SetupProfileException if credentials are invalid
     */
    private fun validateAwsCredentials(
        credentials: CoreCredentials,
        regionObj: Region,
    ) {
        eventBus.emit(Event.Setup.ValidatingCredentials)

        try {
            val tempAWS = createAwsClient(credentials, regionObj)
            tempAWS.checkPermissions()

            eventBus.emit(Event.Setup.CredentialsValidSuccess)

            offerIamPolicyDisplay(tempAWS)
        } catch (e: Exception) {
            eventBus.emit(Event.Setup.CredentialsValidFailed)
            throw SetupProfileException("AWS credentials are invalid", e)
        }
    }

    /**
     * Creates an AWS client using either profile or static credentials.
     */
    private fun createAwsClient(
        credentials: CoreCredentials,
        regionObj: Region,
    ): AWS =
        if (credentials.awsProfile.isNotEmpty()) {
            awsClientFactory.createAWSClientWithProfile(credentials.awsProfile, regionObj)
        } else {
            awsClientFactory.createAWSClient(credentials.awsAccessKey, credentials.awsSecret, regionObj)
        }

    /**
     * Offers to display IAM policies with the user's account ID.
     */
    private fun offerIamPolicyDisplay(aws: AWS) {
        val showPolicies =
            prompter.prompt(
                "Do you want to see the IAM policies with your account ID populated?",
                "N",
            )
        if (showPolicies.equals("y", true)) {
            val accountId = aws.getAccountId()
            displayIAMPolicies(accountId)
        }
    }

    /**
     * Creates the initial User configuration from collected credentials.
     */
    private fun createInitialUserConfig(
        credentials: CoreCredentials,
        existingConfig: Map<String, Any>,
    ): User =
        User(
            email = credentials.email,
            region = credentials.region,
            keyName = existingConfig["keyName"] as? String ?: "",
            awsProfile = credentials.awsProfile,
            awsAccessKey = credentials.awsAccessKey,
            awsSecret = credentials.awsSecret,
            axonOpsOrg = existingConfig["axonOpsOrg"] as? String ?: "",
            axonOpsKey = existingConfig["axonOpsKey"] as? String ?: "",
            tailscaleClientId = existingConfig["tailscaleClientId"] as? String ?: "",
            tailscaleClientSecret = existingConfig["tailscaleClientSecret"] as? String ?: "",
            tailscaleTag = existingConfig["tailscaleTag"] as? String ?: Constants.Tailscale.DEFAULT_DEVICE_TAG,
            s3Bucket = existingConfig["s3Bucket"] as? String ?: "",
        )

    /**
     * Collects optional configuration info and saves the updated config.
     * Used during initial setup - skips prompts for fields with no default.
     */
    private fun collectAndSaveOptionalInfo(
        existingConfig: Map<String, Any>,
        userConfig: User,
    ) {
        val axonOpsOrg =
            promptIfMissing(
                existingConfig,
                PromptField("axonOpsOrg", "AxonOps Org", "", skippable = true),
            )
        val axonOpsKey =
            promptIfMissing(
                existingConfig,
                PromptField("axonOpsKey", "AxonOps Key", "", secret = true, skippable = true),
            )

        userConfig.axonOpsOrg = axonOpsOrg
        userConfig.axonOpsKey = axonOpsKey

        // Collect optional Tailscale credentials
        eventBus.emit(Event.Setup.TailscaleSetupInstructions(Constants.Tailscale.DEFAULT_DEVICE_TAG))
        val tailscaleClientId =
            promptIfMissing(
                existingConfig,
                PromptField("tailscaleClientId", "Tailscale OAuth Client ID", "", skippable = true),
            )
        val tailscaleClientSecret =
            promptIfMissing(
                existingConfig,
                PromptField("tailscaleClientSecret", "Tailscale OAuth Client Secret", "", secret = true, skippable = true),
            )

        userConfig.tailscaleClientId = tailscaleClientId
        userConfig.tailscaleClientSecret = tailscaleClientSecret

        userConfigProvider.saveUserConfig(userConfig)
        eventBus.emit(Event.Setup.ConfigurationSaved)
    }

    /**
     * Collects optional configuration info for update mode.
     * Always prompts for each field, showing current value as default.
     * Empty input keeps the existing value.
     */
    private fun collectAndSaveOptionalInfoWithUpdate(
        existingConfig: Map<String, Any>,
        userConfig: User,
    ) {
        // AxonOps settings
        eventBus.emit(Event.Setup.AxonOpsConfigHeader)
        userConfig.axonOpsOrg =
            promptForUpdate(
                "AxonOps Org",
                existingConfig["axonOpsOrg"] as? String ?: "",
            )
        userConfig.axonOpsKey =
            promptForUpdate(
                "AxonOps Key",
                existingConfig["axonOpsKey"] as? String ?: "",
                secret = true,
            )

        // Tailscale settings
        eventBus.emit(Event.Setup.TailscaleConfigHeader(Constants.Tailscale.DEFAULT_DEVICE_TAG))
        userConfig.tailscaleClientId =
            promptForUpdate(
                "Tailscale OAuth Client ID",
                existingConfig["tailscaleClientId"] as? String ?: "",
            )
        userConfig.tailscaleClientSecret =
            promptForUpdate(
                "Tailscale OAuth Client Secret",
                existingConfig["tailscaleClientSecret"] as? String ?: "",
                secret = true,
            )

        userConfigProvider.saveUserConfig(userConfig)
        eventBus.emit(Event.Setup.ConfigSectionSaved)
    }

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
            value.length == 1 -> "${value[0]}****"
            else -> "${value[0]}****"
        }

    /**
     * Ensures all required AWS resources exist.
     */
    private fun ensureAwsResources(
        userConfig: User,
        credentials: CoreCredentials,
        regionObj: Region,
    ) {
        ensureKeyPair(userConfig, credentials, regionObj)
        ensureIamRoles(userConfig)
        ensureS3Bucket(userConfig, credentials, regionObj)
        ensurePackerVpc()
        ensureAmi(userConfig)
    }

    /**
     * Generates an AWS key pair if one doesn't exist.
     */
    private fun ensureKeyPair(
        userConfig: User,
        credentials: CoreCredentials,
        regionObj: Region,
    ) {
        if (userConfig.keyName.isBlank()) {
            eventBus.emit(Event.Setup.GeneratingKeyPair)
            val ec2Client = createEc2Client(credentials, regionObj)
            val keyName =
                User.generateAwsKeyPair(
                    context,
                    ec2Client,
                    eventBus,
                )
            userConfig.keyName = keyName
            userConfigProvider.saveUserConfig(userConfig)
            eventBus.emit(Event.Setup.KeyPairSaved)
        }
    }

    /**
     * Creates an EC2 client using either profile or static credentials.
     */
    private fun createEc2Client(
        credentials: CoreCredentials,
        regionObj: Region,
    ) = if (credentials.awsProfile.isNotEmpty()) {
        awsClientFactory.createEc2ClientWithProfile(credentials.awsProfile, regionObj)
    } else {
        awsClientFactory.createEc2Client(credentials.awsAccessKey, credentials.awsSecret, regionObj)
    }

    /**
     * Ensures IAM roles are configured.
     */
    private fun ensureIamRoles(userConfig: User) {
        eventBus.emit(Event.Setup.IamRolesConfiguring)
        awsResourceSetup.ensureAWSResources(userConfig)
        eventBus.emit(Event.Setup.IamRolesValidated)
    }

    /**
     * Creates an S3 bucket for shared resources if one doesn't exist.
     */
    private fun ensureS3Bucket(
        userConfig: User,
        credentials: CoreCredentials,
        regionObj: Region,
    ) {
        if (userConfig.s3Bucket.isBlank()) {
            eventBus.emit(Event.Setup.S3BucketCreating)
            val bucketName = "easy-db-lab-${java.util.UUID.randomUUID()}"

            val awsClient = createAwsClient(credentials, regionObj)
            awsClient.createS3Bucket(bucketName)
            awsClient.putS3BucketPolicy(bucketName)
            awsClient.tagS3Bucket(
                bucketName,
                mapOf(
                    "Profile" to context.profile,
                    "Owner" to credentials.email,
                    "easy_cass_lab" to "1",
                ),
            )

            userConfig.s3Bucket = bucketName
            userConfigProvider.saveUserConfig(userConfig)
            eventBus.emit(Event.Setup.S3BucketCreated(bucketName))
        }
    }

    /**
     * Creates Packer VPC infrastructure.
     */
    private fun ensurePackerVpc() {
        eventBus.emit(Event.Setup.PackerVpcCreating)
        awsInfra.ensurePackerInfrastructure(Constants.Network.SSH_PORT)
        eventBus.emit(Event.Setup.PackerVpcReady)
    }

    /**
     * Validates that a required AMI exists, offering to build one if not found.
     */
    private fun ensureAmi(userConfig: User) {
        eventBus.emit(Event.Setup.CheckingAmi)
        val archType = Arch.AMD64

        try {
            amiValidator.validateAMI(overrideAMI = "", requiredArchitecture = archType)
            eventBus.emit(Event.Setup.AmiFound(archType.type))
        } catch (e: AMIValidationException.NoAMIFound) {
            handleMissingAmi(archType, userConfig)
        }
    }

    /**
     * Handles the case when no AMI is found - prompts user and optionally builds one.
     */
    private fun handleMissingAmi(
        archType: Arch,
        userConfig: User,
    ) {
        eventBus.emit(Event.Setup.AmiNotFound(archType.type))

        val proceed = prompter.prompt("Press Enter to start building the AMI, or type 'skip' to exit setup", "")

        if (proceed.equals("skip", ignoreCase = true)) {
            eventBus.emit(Event.Setup.AmiSkipped)
            return
        }

        buildAmi(archType, userConfig)
    }

    /**
     * Builds an AMI for the specified architecture.
     */
    private fun buildAmi(
        archType: Arch,
        userConfig: User,
    ) {
        try {
            eventBus.emit(Event.Setup.AmiBuildStarting(archType.type))

            commandExecutor.execute {
                BuildImage().apply {
                    buildArgs.arch = archType
                    buildArgs.region = userConfig.region
                }
            }

            eventBus.emit(Event.Setup.AmiBuildSuccess)
        } catch (buildError: Exception) {
            eventBus.emit(Event.Setup.AmiBuildFailed(buildError.message ?: "unknown error", archType.type, userConfig.region))
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
        val policies = User.getRequiredIAMPolicies(accountId)
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
        existingConfig: Map<String, Any>,
        field: PromptField,
    ): String {
        if (existingConfig.containsKey(field.fieldName)) {
            return existingConfig[field.fieldName] as String
        }

        if (field.skippable && field.default.isEmpty()) {
            return ""
        }

        return prompter.prompt(field.prompt, field.default, field.secret)
    }

    /**
     * Convenience overload for non-skippable, non-secret fields.
     */
    private fun promptIfMissing(
        existingConfig: Map<String, Any>,
        fieldName: String,
        prompt: String,
        default: String,
    ): String = promptIfMissing(existingConfig, PromptField(fieldName, prompt, default))

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
     * Data class to hold core credentials collected during setup.
     */
    private data class CoreCredentials(
        val email: String,
        val region: String,
        val awsProfile: String,
        val awsAccessKey: String,
        val awsSecret: String,
    )
}
