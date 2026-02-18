package com.rustyrazorblade.easydblab.commands

import com.github.ajalt.mordant.TermColors
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Prompter
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.Policy
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
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
        outputHandler.handleMessage("Profile '${context.profile}' is already configured.")
        outputHandler.handleMessage("Press Enter to keep existing value, or type a new value to update.\n")

        // Load existing config as User object
        val userConfig = userConfigProvider.getUserConfig()

        // Collect optional info, allowing updates
        collectAndSaveOptionalInfoWithUpdate(existingConfig, userConfig)

        with(TermColors()) {
            outputHandler.handleMessage(green("\nConfiguration updated!"))
        }
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
        outputHandler.handleMessage("Credentials saved")

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
                with(TermColors()) {
                    outputHandler.handleMessage(
                        yellow("\nCredential validation failed. Please try again. (Attempt $attempt of $MAX_CREDENTIAL_RETRIES)\n"),
                    )
                }
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
        outputHandler.handleMessage("Your email will be added to AWS resource tags to identify the owner.")
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
        outputHandler.handleMessage("Validating AWS credentials...")

        try {
            val tempAWS = createAwsClient(credentials, regionObj)
            tempAWS.checkPermissions()

            with(TermColors()) {
                outputHandler.handleMessage(green("AWS credentials validated successfully"))
            }

            offerIamPolicyDisplay(tempAWS)
        } catch (e: Exception) {
            with(TermColors()) {
                outputHandler.handleMessage(
                    red(
                        """

                        AWS credentials are invalid. Please check your credentials.

                        """.trimIndent(),
                    ),
                )
            }
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
        outputHandler.handleMessage(
            """

            --- Tailscale VPN Setup (optional) ---
            Tailscale provides secure VPN access to your cluster.

            Setup steps:
            1. Go to https://login.tailscale.com/admin/acls
            2. Add to your ACL policy:
               "tagOwners": {
                 "${Constants.Tailscale.DEFAULT_DEVICE_TAG}": ["autogroup:admin"]
               }
            3. Go to https://login.tailscale.com/admin/settings/oauth
            4. Create OAuth client with 'Devices: Write' scope
            5. Under 'Add tags', add: ${Constants.Tailscale.DEFAULT_DEVICE_TAG}

            """.trimIndent(),
        )
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
        outputHandler.handleMessage("Configuration saved")
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
        outputHandler.handleMessage("--- AxonOps Configuration ---")
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
        outputHandler.handleMessage(
            """

            --- Tailscale VPN Configuration ---
            Setup steps (if not already done):
            1. Go to https://login.tailscale.com/admin/acls
            2. Add to your ACL policy:
               "tagOwners": {
                 "${Constants.Tailscale.DEFAULT_DEVICE_TAG}": ["autogroup:admin"]
               }
            3. Go to https://login.tailscale.com/admin/settings/oauth
            4. Create OAuth client with 'Devices: Write' scope
            5. Under 'Add tags', add: ${Constants.Tailscale.DEFAULT_DEVICE_TAG}
            """.trimIndent(),
        )
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
        outputHandler.handleMessage("\nConfiguration saved")
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
            outputHandler.handleMessage("Generating AWS key pair...")
            val ec2Client = createEc2Client(credentials, regionObj)
            val keyName =
                User.generateAwsKeyPair(
                    context,
                    ec2Client,
                    outputHandler,
                )
            userConfig.keyName = keyName
            userConfigProvider.saveUserConfig(userConfig)
            outputHandler.handleMessage("Key pair saved")
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
        outputHandler.handleMessage("Ensuring IAM roles are configured...")
        awsResourceSetup.ensureAWSResources(userConfig)
        outputHandler.handleMessage("IAM resources validated")
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
            outputHandler.handleMessage("Creating S3 bucket for shared resources...")
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
            outputHandler.handleMessage("S3 bucket created: $bucketName")
        }
    }

    /**
     * Creates Packer VPC infrastructure.
     */
    private fun ensurePackerVpc() {
        outputHandler.handleMessage("Creating Packer VPC infrastructure...")
        awsInfra.ensurePackerInfrastructure(Constants.Network.SSH_PORT)
        outputHandler.handleMessage("Packer VPC infrastructure ready")
    }

    /**
     * Validates that a required AMI exists, offering to build one if not found.
     */
    private fun ensureAmi(userConfig: User) {
        outputHandler.handleMessage("Checking for required AMI...")
        val archType = Arch.AMD64

        try {
            amiValidator.validateAMI(overrideAMI = "", requiredArchitecture = archType)
            outputHandler.handleMessage("AMI found for ${archType.type} architecture")
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
        with(TermColors()) {
            outputHandler.handleMessage(
                yellow(
                    """

                    AMI not found for ${archType.type} architecture.

                    The system needs to build a custom AMI for your architecture.
                    This process takes approximately 10-15 minutes.

                    """.trimIndent(),
                ),
            )
        }

        val proceed = prompter.prompt("Press Enter to start building the AMI, or type 'skip' to exit setup", "")

        if (proceed.equals("skip", ignoreCase = true)) {
            outputHandler.handleMessage("Setup cancelled. Run 'easy-db-lab build-image' to build the AMI later.")
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
            outputHandler.handleMessage("Building AMI for ${archType.type} architecture...")

            commandExecutor.execute {
                BuildImage().apply {
                    buildArgs.arch = archType
                    buildArgs.region = userConfig.region
                }
            }

            with(TermColors()) {
                outputHandler.handleMessage(green("AMI build completed successfully"))
            }
        } catch (buildError: Exception) {
            with(TermColors()) {
                outputHandler.handleMessage(
                    red(
                        """

                        Failed to build AMI: ${buildError.message}

                        You can manually build the AMI later by running:
                          easy-db-lab build-image --arch ${archType.type} --region ${userConfig.region}

                        """.trimIndent(),
                    ),
                )
            }
        }
    }

    /**
     * Shows the success message after setup completes.
     */
    private fun showSuccessMessage() {
        with(TermColors()) {
            outputHandler.handleMessage(green("\nAccount setup complete!"))
        }
    }

    private fun showWelcomeMessage() {
        outputHandler.handleMessage(
            """
            Welcome to the easy-db-lab interactive setup for profile '${context.profile}'.
            (To use a different profile, set the EASY_DB_LAB_PROFILE environment variable)

            **** IMPORTANT ****

            This tool provisions and destroys AWS infrastructure!

            We strongly recommend using a separate AWS account under an organization
            for lab environments to isolate costs and resources from production.

            *******************

            We need AWS credentials for the account that will be used in this environment.

            During setup, we will create the following AWS resources:
              • EC2 key pair for SSH access to instances
              • IAM role for instance permissions
              • S3 bucket (shared across all labs in this profile)
              • Packer VPC infrastructure for building AMIs

            Lab environments may also require permissions to start a Spark cluster via EMR if requested.

            You will be asked if you want to see the required IAM permissions before
            entering your credentials.

            OPTIONAL: We can automatically configure AxonOps for free Cassandra monitoring.
            To use this feature, create an account at https://axonops.com/ and obtain
            your organization name and API key from: Agent Setup → Keys

            Let's gather some information to get started.
            """.trimIndent(),
        )
    }

    /**
     * Displays IAM policies without confirmation prompt.
     * Used when user opts to see policies during setup.
     *
     * @param accountId The AWS account ID to substitute in policies
     */
    private fun displayIAMPolicies(accountId: String) {
        val policies = User.getRequiredIAMPolicies(accountId)
        displayIamPolicyHeader()
        displayPolicyBodies(policies)
        displayIamPolicyFooter()
    }

    private fun displayIamPolicyHeader() {
        outputHandler.handleMessage(
            """

            ========================================
            AWS IAM PERMISSIONS REQUIRED
            ========================================

            RECOMMENDED APPROACH: Managed Policies on a Group

            Best for teams with multiple users:
              • No size limits (inline policies limited to 5,120 bytes total)
              • Required for EMR/Spark cluster functionality
              • Easier to update and manage
              • Reusable across multiple users

            """.trimIndent(),
        )
    }

    private fun displayPolicyBodies(policies: List<Policy>) {
        policies.forEachIndexed { index, policy ->
            with(TermColors()) {
                outputHandler.handleMessage(
                    """
                    ${green("========================================")}
                    ${green("Policy ${index + 1}: ${policy.name}")}
                    ${green("========================================")}

                    ${policy.body}

                    """.trimIndent(),
                )
            }
        }
    }

    private fun displayIamPolicyFooter() {
        outputHandler.handleMessage(
            """
            ========================================

            SETUP STEPS (Managed Policies on Group):

              1. Create IAM group (e.g., "EasyDBLabUsers")
                 IAM Console → Groups → Create Group

              2. Create three managed policies from JSON above:
                 IAM Console → Policies → Create Policy
                 • Select JSON tab and paste policy content
                 • Name: EasyDBLabEC2, EasyDBLabIAM, EasyDBLabEMR

              3. Attach all three managed policies to your group:
                 Groups → Your Group → Permissions → Attach Policy
                 • Select EasyDBLabEC2, EasyDBLabIAM, EasyDBLabEMR

              4. Add your IAM user(s) to the group:
                 Groups → Your Group → Users → Add Users

            ALTERNATIVE (Single User Only):
              • Attach as inline policies directly to your IAM user
              • WARNING: May hit 5,120 byte limit (won't fit all three policies)
              • Not recommended if using EMR/Spark clusters

            ========================================

            """.trimIndent(),
        )
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
