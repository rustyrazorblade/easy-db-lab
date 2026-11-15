package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.kernel.ProviderErrorHandler
import com.rustyrazorblade.easydblab.providers.aws.ssm.SdkSsmSessionTerminator
import com.rustyrazorblade.easydblab.providers.aws.ssm.SsmCliCredentials
import com.rustyrazorblade.easydblab.providers.aws.ssm.SsmProxyWrapper
import com.rustyrazorblade.easydblab.providers.aws.ssm.SsmSessionCommandBuilder
import com.rustyrazorblade.easydblab.providers.aws.ssm.SsmSessionTerminator
import org.koin.dsl.bind
import org.koin.dsl.module
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ecr.EcrClient
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.opensearch.OpenSearchClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.sts.StsClient
import java.time.Duration

/**
 * Creates a ClientOverrideConfiguration.
 * Note: AWS SDK instrumentation is handled automatically by the OpenTelemetry Java agent.
 */
private fun createClientOverrideConfig(): ClientOverrideConfiguration = ClientOverrideConfiguration.builder().build()

/**
 * Koin module for the AWS adapter's SDK-facing bindings.
 *
 * Provides the region, the credentials provider, the SDK clients, the low-level [AWS] wrapper,
 * [AWSClientFactory], [AWSCredentialsManager] (a factory), the [AwsErrorHandler] registered as a
 * [ProviderErrorHandler], and the SSM Session Manager transport bindings. Its only edges outside
 * `providers/aws` are `configuration`, `events` and `Context`; the bindings of `services/aws`
 * classes live in `AwsServicesModule`.
 */
val awsClientsModule =
    module {
        // Recognises AWS credential and permission failures; takes no profile, so it resolves before one exists
        single { AwsErrorHandler() } bind ProviderErrorHandler::class

        // Provide AWS region as singleton
        single { Region.of(get<User>().region) }

        // Provide credentials provider based on User configuration
        single<AwsCredentialsProvider> {
            val user = get<User>()
            when {
                // If awsProfile is set, use ProfileCredentialsProvider
                user.awsProfile.isNotEmpty() -> ProfileCredentialsProvider.create(user.awsProfile)
                // Otherwise use static credentials from User
                else ->
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(user.awsAccessKey, user.awsSecret),
                    )
            }
        }

        // Provide AWS SDK clients as singletons with credentials and optional telemetry
        single {
            val overrideConfig = createClientOverrideConfig()
            IamClient
                .builder()
                .region(get<Region>())
                .credentialsProvider(get<AwsCredentialsProvider>())
                .overrideConfiguration(overrideConfig)
                .build()
        }

        single {
            val overrideConfig = createClientOverrideConfig()
            Ec2Client
                .builder()
                .region(get<Region>())
                .credentialsProvider(get<AwsCredentialsProvider>())
                .overrideConfiguration(overrideConfig)
                .build()
        }

        single {
            val overrideConfig = createClientOverrideConfig()
            S3Client
                .builder()
                .region(get<Region>())
                .credentialsProvider(get<AwsCredentialsProvider>())
                .overrideConfiguration(overrideConfig)
                .build()
        }

        single {
            val overrideConfig = createClientOverrideConfig()
            StsClient
                .builder()
                .region(get<Region>())
                .credentialsProvider(get<AwsCredentialsProvider>())
                .overrideConfiguration(overrideConfig)
                .build()
        }

        single {
            val overrideConfig = createClientOverrideConfig()
            EmrClient
                .builder()
                .region(get<Region>())
                .credentialsProvider(get<AwsCredentialsProvider>())
                .overrideConfiguration(overrideConfig)
                .build()
        }

        single {
            val overrideConfig = createClientOverrideConfig()
            OpenSearchClient
                .builder()
                .region(get<Region>())
                .credentialsProvider(get<AwsCredentialsProvider>())
                .overrideConfiguration(overrideConfig)
                .build()
        }

        single {
            val overrideConfig = createClientOverrideConfig()
            EcrClient
                .builder()
                .region(get<Region>())
                .credentialsProvider(get<AwsCredentialsProvider>())
                .overrideConfiguration(overrideConfig)
                .build()
        }

        // Low-level IAM, S3 and STS operations
        single { AWS(get<IamClient>(), get<S3Client>(), get<StsClient>()) }

        // Provide AWSClientFactory for creating AWS clients with custom credentials
        single<AWSClientFactory> { DefaultAWSClientFactory() }

        // Provide the credentials file manager for the AWS CLI (Packer, SSM sessions). A factory, not a
        // single: its credentials file is lazy, and a single would keep stale SSO credentials across
        // builds in REPL or Server mode.
        factory { AWSCredentialsManager(get<Context>().profileDir, get<AwsCredentialsProvider>()) }

        // The account compactor: clients for the account bucket's region and every enabled region.
        single<RegionalClients> { DefaultRegionalClients(get()) }

        // Role policy set for the EC2 instance role
        single { InstanceRolePolicies(get()) }

        // SSM Session Manager SSH transport
        single {
            val user = get<User>()
            val credentialsManager = get<AWSCredentialsManager>()
            val profileDir = get<Context>().profileDir
            SsmSessionCommandBuilder(
                user.region,
                { SsmCliCredentials.forUser(user, credentialsManager) },
                sshProxyWrapper = { SsmProxyWrapper(profileDir).install() },
            )
        }

        single<SsmSessionTerminator> {
            val region = Region.of(get<User>().region)
            val credentials = get<AwsCredentialsProvider>()
            // The client is built on first use, off the main thread, not here at Koin resolution.
            SdkSsmSessionTerminator(credentials) {
                SsmClient
                    .builder()
                    .region(region)
                    .credentialsProvider(credentials)
                    // Bounded per attempt and overall, so the SDK's retries fit in the stop's 20s budget.
                    .overrideConfiguration { config ->
                        config
                            .apiCallAttemptTimeout(Duration.ofSeconds(Constants.Ssm.TERMINATE_SESSION_ATTEMPT_TIMEOUT_SECONDS))
                            .apiCallTimeout(Duration.ofSeconds(Constants.Ssm.TERMINATE_SESSION_TIMEOUT_SECONDS))
                    }.build()
            }
        }
    }
