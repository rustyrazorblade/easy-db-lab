package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.AWSResourceSetupService
import com.rustyrazorblade.easydblab.services.ObjectStore
import com.rustyrazorblade.easydblab.services.SparkService
import com.rustyrazorblade.easydblab.services.VictoriaLogsService
import org.koin.dsl.module
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.opensearch.OpenSearchClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sts.StsClient

/**
 * Koin module for AWS service dependency injection.
 *
 * Provides:
 * - Region: AWS region configuration
 * - AwsCredentialsProvider: Credentials provider using User configuration
 * - IamClient: AWS IAM client for identity and access management
 * - Ec2Client: AWS EC2 client for instance and AMI management
 * - EmrClient: AWS EMR client for Elastic MapReduce (Spark) cluster management
 * - S3Client: AWS S3 client for object storage operations
 * - StsClient: AWS STS client for credential validation
 * - AWS: AWS service wrapper for lab environment operations
 * - EC2Service: Low-level EC2 AMI operations
 * - AMIService: High-level AMI lifecycle management
 * - AMIValidator: AMI validation service with retry logic and architecture verification
 * - VpcService: Generic VPC infrastructure management
 * - AwsInfrastructureService: VPC infrastructure orchestration for packer and clusters
 * - SparkService: Spark job lifecycle management for EMR clusters
 * - ObjectStore: Cloud-agnostic object storage interface (S3 implementation)
 * - SqsClient: AWS SQS client for message queue operations
 * - SQSService: SQS queue management for log ingestion
 *
 * Note: AWSCredentialsManager is no longer registered here - it's created directly by
 * Packer classes that need it, since they already have Context.
 */
val awsModule =
    module {
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

        // Provide AWS SDK clients as singletons with credentials
        single {
            IamClient
                .builder()
                .region(get<Region>())
                .credentialsProvider(get<AwsCredentialsProvider>())
                .build()
        }

        single {
            Ec2Client
                .builder()
                .region(get<Region>())
                .credentialsProvider(get<AwsCredentialsProvider>())
                .build()
        }

        single {
            S3Client
                .builder()
                .region(get<Region>())
                .credentialsProvider(get<AwsCredentialsProvider>())
                .build()
        }

        single {
            StsClient
                .builder()
                .region(get<Region>())
                .credentialsProvider(get<AwsCredentialsProvider>())
                .build()
        }

        single {
            EmrClient
                .builder()
                .region(get<Region>())
                .credentialsProvider(get<AwsCredentialsProvider>())
                .build()
        }

        single {
            OpenSearchClient
                .builder()
                .region(get<Region>())
                .credentialsProvider(get<AwsCredentialsProvider>())
                .build()
        }

        single {
            SqsClient
                .builder()
                .region(get<Region>())
                .credentialsProvider(get<AwsCredentialsProvider>())
                .build()
        }

        // Provide AWS service as singleton
        single { AWS(get<IamClient>(), get<S3Client>(), get<StsClient>()) }

        // Provide EC2Service as singleton
        single { EC2Service(get<Ec2Client>()) }

        // Provide EC2InstanceService as singleton
        single {
            EC2InstanceService(
                get<Ec2Client>(),
                get<OutputHandler>(),
            )
        }

        // Provide AMIService as singleton
        single { AMIService(get<EC2Service>()) }

        // Provide AMIValidator as singleton
        single<AMIValidator> {
            AMIValidationService(
                get<EC2Service>(),
                get<OutputHandler>(),
                get<AWS>(),
            )
        }

        // Provide VpcService as singleton
        single<VpcService> {
            EC2VpcService(
                get<Ec2Client>(),
                get<OutputHandler>(),
            )
        }

        // Provide AwsInfrastructureService as singleton
        single {
            AwsInfrastructureService(
                get<VpcService>(),
                get<OutputHandler>(),
            )
        }

        // Provide AWSResourceSetupService as singleton
        single {
            AWSResourceSetupService(
                get<AWS>(),
                get<OutputHandler>(),
            )
        }

        // Provide AWSClientFactory for creating AWS clients with custom credentials
        single<AWSClientFactory> { DefaultAWSClientFactory() }

        // Provide ObjectStore implementation (S3) as singleton
        single<ObjectStore> {
            S3ObjectStore(
                get<S3Client>(),
                get<OutputHandler>(),
            )
        }

        // Provide SparkService implementation (EMR) as singleton
        single<SparkService> {
            EMRSparkService(
                get<EmrClient>(),
                get<OutputHandler>(),
                get<ObjectStore>(),
                get<ClusterStateManager>(),
                get<VictoriaLogsService>(),
            )
        }

        // Provide EMRService as singleton
        single {
            EMRService(
                get<EmrClient>(),
                get<OutputHandler>(),
            )
        }

        // Provide EMRTeardownService as singleton
        single {
            EMRTeardownService(
                get<EmrClient>(),
                get<OutputHandler>(),
            )
        }

        // Provide OpenSearchService as singleton
        single {
            OpenSearchService(
                get<OpenSearchClient>(),
                get<OutputHandler>(),
            )
        }

        // Provide InfrastructureTeardownService as singleton
        single {
            InfrastructureTeardownService(
                get<VpcService>(),
                get<EMRTeardownService>(),
                get<OpenSearchService>(),
                get<OutputHandler>(),
            )
        }

        // Provide SecurityGroupService as singleton
        single<SecurityGroupService> {
            EC2SecurityGroupService(get<Ec2Client>())
        }

        // Provide AMIResolver as singleton
        single<AMIResolver> {
            DefaultAMIResolver(get<EC2Service>())
        }

        // Provide InstanceSpecFactory as singleton
        single<InstanceSpecFactory> { DefaultInstanceSpecFactory() }

        // Provide SQSService as singleton
        single<SQSService> {
            AWSSQSService(
                get<SqsClient>(),
                get<OutputHandler>(),
            )
        }
    }
