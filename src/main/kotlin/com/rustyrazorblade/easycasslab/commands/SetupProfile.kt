package com.rustyrazorblade.easycasslab.commands

import com.github.ajalt.mordant.TermColors
import com.rustyrazorblade.easycasslab.Constants
import com.rustyrazorblade.easycasslab.Context
import com.rustyrazorblade.easycasslab.Utils
import com.rustyrazorblade.easycasslab.configuration.Arch
import com.rustyrazorblade.easycasslab.configuration.User
import com.rustyrazorblade.easycasslab.configuration.UserConfigProvider
import com.rustyrazorblade.easycasslab.providers.aws.AMIValidationException
import com.rustyrazorblade.easycasslab.providers.aws.AMIValidator
import com.rustyrazorblade.easycasslab.providers.aws.AWS
import com.rustyrazorblade.easycasslab.providers.aws.AwsInfrastructureService
import com.rustyrazorblade.easycasslab.services.AWSResourceSetupService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sts.StsClient
import kotlin.system.exitProcess

/**
 * Sets up user profile interactively.
 */
@Command(
    name = "setup-profile",
    aliases = ["setup"],
    description = ["Set up user profile interactively"],
)
class SetupProfile(
    context: Context,
) : PicoBaseCommand(context) {
    private val userConfigProvider: UserConfigProvider by inject()
    private val awsResourceSetup: AWSResourceSetupService by inject()
    private val awsInfra: AwsInfrastructureService by inject()
    private val amiValidator: AMIValidator by inject()

    override fun execute() {
        // Load existing config values if file exists
        val existingConfig = userConfigProvider.loadExistingConfig()

        // Track if we need to prompt for any new fields
        val needsPrompting =
            !existingConfig.containsKey("email") ||
                !existingConfig.containsKey("region") ||
                !existingConfig.containsKey("awsAccessKey") ||
                !existingConfig.containsKey("awsSecret")

        // If all required fields exist, just load and return
        if (!needsPrompting) {
            outputHandler.handleMessage("Profile is already set up!")
            return
        }

        // Show welcome message at the start
        showWelcomeMessage()

        // PHASE 1: Collect email (with resource tag note)
        outputHandler.handleMessage("Your email will be added to AWS resource tags to identify the owner.")
        val email = promptIfMissing(existingConfig, "email", "What's your email?", "")

        // PHASE 2: Collect AWS credentials
        val region = promptIfMissing(existingConfig, "region", "What AWS region do you use?", "us-west-2")
        val awsAccessKey = promptIfMissing(existingConfig, "awsAccessKey", "Please enter your AWS Access Key", "")
        val awsSecret =
            promptIfMissing(
                existingConfig,
                "awsSecret",
                "Please enter your AWS Secret Access Key",
                "",
                secret = true,
            )

        // Validate AWS credentials before asking remaining questions
        outputHandler.handleMessage("Validating AWS credentials...")

        val regionObj = Region.of(region)
        try {
            val tempAWS = createTemporaryAWSClient(awsAccessKey, awsSecret, regionObj)
            tempAWS.checkPermissions()

            with(TermColors()) {
                outputHandler.handleMessage(green("AWS credentials validated successfully"))
            }

            // Ask if user wants to see IAM policies with actual account ID (after validation)
            val showPolicies =
                Utils.prompt(
                    "Do you want to see the IAM policies with your account ID populated?",
                    "N",
                )
            if (showPolicies.equals("y", true)) {
                val accountId = tempAWS.getAccountId()
                displayIAMPolicies(accountId)
            }
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
            exitProcess(1)
        }

        // Save credentials after successful validation
        val userConfig =
            User(
                email = email,
                region = region,
                keyName = existingConfig["keyName"] as? String ?: "",
                sshKeyPath = existingConfig["sshKeyPath"] as? String ?: "",
                awsProfile = existingConfig["awsProfile"] as? String ?: "",
                awsAccessKey = awsAccessKey,
                awsSecret = awsSecret,
                axonOpsOrg = existingConfig["axonOpsOrg"] as? String ?: "",
                axonOpsKey = existingConfig["axonOpsKey"] as? String ?: "",
                s3Bucket = existingConfig["s3Bucket"] as? String ?: "",
            )

        userConfigProvider.saveUserConfig(userConfig)
        outputHandler.handleMessage("Credentials saved")

        // PHASE 3: Collect AxonOps info (after validation, before resource creation)
        val axonOpsOrg = promptIfMissing(existingConfig, "axonOpsOrg", "AxonOps Org", "", skippable = true)
        val axonOpsKey =
            promptIfMissing(
                existingConfig,
                "axonOpsKey",
                "AxonOps Key",
                "",
                secret = true,
                skippable = true,
            )

        // Collect remaining fields
        val awsProfile = promptIfMissing(existingConfig, "awsProfile", "AWS Profile", "", skippable = true)

        // Update user config with all collected fields and save
        userConfig.axonOpsOrg = axonOpsOrg
        userConfig.axonOpsKey = axonOpsKey
        userConfig.awsProfile = awsProfile

        userConfigProvider.saveUserConfig(userConfig)
        outputHandler.handleMessage("Configuration saved")

        // PHASE 4: AWS Operations (after all questions answered)

        // Generate AWS keys only when both keyName and sshKeyPath are missing
        if (userConfig.keyName.isBlank() || userConfig.sshKeyPath.isBlank()) {
            outputHandler.handleMessage("Generating AWS key pair...")

            val (keyName, sshKeyPath) = User.generateAwsKeyPair(context, awsAccessKey, awsSecret, regionObj, outputHandler)

            userConfig.keyName = keyName
            userConfig.sshKeyPath = sshKeyPath

            // Save config with key pair
            userConfigProvider.saveUserConfig(userConfig)
            outputHandler.handleMessage("Key pair saved")
        }

        // Create S3 bucket and IAM role if not already present
        if (userConfig.s3Bucket.isBlank()) {
            outputHandler.handleMessage("Creating AWS resources (S3 bucket, IAM role)...")

            awsResourceSetup.ensureAWSResources(userConfig)
            // Service saves config automatically

            outputHandler.handleMessage("AWS resources created successfully")
        }

        // Create Packer VPC infrastructure
        outputHandler.handleMessage("Creating Packer VPC infrastructure...")

        awsInfra.ensurePackerInfrastructure(Constants.Network.SSH_PORT)

        outputHandler.handleMessage("Packer VPC infrastructure ready")

        // Validate AMI and build if not found
        outputHandler.handleMessage("Checking for required AMI...")

        val archType = Arch.AMD64

        try {
            amiValidator.validateAMI(
                overrideAMI = "",
                requiredArchitecture = archType,
            )
            outputHandler.handleMessage("AMI found for ${archType.type} architecture")
        } catch (e: AMIValidationException.NoAMIFound) {
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

            // Prompt user to continue
            val proceed = Utils.prompt("Press Enter to start building the AMI, or type 'skip' to exit setup", "")

            if (proceed.equals("skip", ignoreCase = true)) {
                outputHandler.handleMessage("Setup cancelled. Run 'easy-cass-lab build-image' to build the AMI later.")
                return
            }

            // Build the AMI
            try {
                outputHandler.handleMessage("Building AMI for ${archType.type} architecture...")

                val buildImage = BuildImage(context)
                buildImage.buildArgs.arch = archType
                buildImage.buildArgs.region = userConfig.region
                buildImage.execute()

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
                              easy-cass-lab build-image --arch ${archType.type} --region ${userConfig.region}

                            """.trimIndent(),
                        ),
                    )
                }
            }
        }

        // Show success message
        with(TermColors()) {
            outputHandler.handleMessage(green("\nAccount setup complete!"))
        }
    }

    private fun showWelcomeMessage() {
        outputHandler.handleMessage(
            """
            Welcome to the easy-cass-lab interactive setup for profile '${context.profile}'.
            (To use a different profile, set the EASY_CASS_LAB_PROFILE environment variable)

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

        outputHandler.handleMessage(
            """
            ========================================

            SETUP STEPS (Managed Policies on Group):

              1. Create IAM group (e.g., "EasyCassLabUsers")
                 IAM Console → Groups → Create Group

              2. Create three managed policies from JSON above:
                 IAM Console → Policies → Create Policy
                 • Select JSON tab and paste policy content
                 • Name: EasyCassLabEC2, EasyCassLabIAM, EasyCassLabEMR

              3. Attach all three managed policies to your group:
                 Groups → Your Group → Permissions → Attach Policy
                 • Select EasyCassLabEC2, EasyCassLabIAM, EasyCassLabEMR

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
     * Creates a temporary AWS client from provided credentials for validation.
     */
    private fun createTemporaryAWSClient(
        accessKey: String,
        secret: String,
        region: Region,
    ): AWS {
        val credentialsProvider =
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secret),
            )

        val iamClient =
            IamClient
                .builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build()

        val s3Client =
            S3Client
                .builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build()

        val stsClient =
            StsClient
                .builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build()

        return AWS(iamClient, s3Client, stsClient, outputHandler)
    }

    /**
     * Helper function to prompt for a field only if it's missing from existing config.
     * Returns existing value if present, prompts user otherwise.
     * Skips prompting for skippable fields with empty defaults.
     */
    private fun promptIfMissing(
        existingConfig: Map<String, Any>,
        fieldName: String,
        prompt: String,
        default: String,
        secret: Boolean = false,
        skippable: Boolean = false,
    ): String {
        // If exists in config, return it
        if (existingConfig.containsKey(fieldName)) {
            return existingConfig[fieldName] as String
        }

        // If skippable and empty default, return empty
        if (skippable && default.isEmpty()) {
            return ""
        }

        // Otherwise prompt
        return Utils.prompt(prompt, default, secret)
    }
}
