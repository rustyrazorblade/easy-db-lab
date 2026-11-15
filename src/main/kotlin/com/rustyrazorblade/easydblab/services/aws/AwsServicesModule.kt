package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.providers.aws.AwsErrorHandler
import com.rustyrazorblade.easydblab.providers.aws.InstanceRolePolicies
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.services.ExternalIpService
import com.rustyrazorblade.easydblab.services.ImagePullSecretService
import com.rustyrazorblade.easydblab.services.LokiQueryService
import com.rustyrazorblade.easydblab.services.ObjectStore
import com.rustyrazorblade.easydblab.services.SparkService
import org.koin.dsl.module
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.opensearch.OpenSearchClient
import software.amazon.awssdk.services.s3.S3Client

/**
 * Koin module for the bindings of every `services/aws` class, plus the cloud-neutral interfaces
 * they implement ([ObjectStore], [SparkService]).
 *
 * The SDK clients, credentials and error handler are in `AwsClientsModule`, so the services layer
 * never constructs an SDK client.
 */
val awsServicesModule =
    module {
        // Provide EC2InstanceService as singleton
        single {
            EC2InstanceService(
                get<Ec2Client>(),
                get<EventBus>(),
                region = get<Region>().id(),
            )
        }

        // Provide AMIService as singleton (also serves as AMIValidator)
        single {
            AMIService(
                get<Ec2Client>(),
                get<EventBus>(),
                get<AWS>(),
            )
        }

        // Bind AMIValidator to the same AMIService instance
        single<AMIValidator> { get<AMIService>() }

        // Provide VpcService as singleton
        single<VpcService> {
            EC2VpcService(
                get<Ec2Client>(),
                get<EventBus>(),
            )
        }

        // Provide AwsInfrastructureService as singleton (handles both creation and teardown)
        single {
            AwsInfrastructureService(
                get<VpcService>(),
                get<EMRService>(),
                get<OpenSearchService>(),
                get<EventBus>(),
            )
        }

        // SSH ingress rules on the workspace cluster's security group (ingress allow / revoke)
        single<SshIngressService> {
            DefaultSshIngressService(
                get<ClusterStateManager>(),
                get<ExternalIpService>(),
                get<VpcService>(),
            )
        }

        single {
            AWSResourceSetupService(
                get<AWS>(),
                get<EventBus>(),
                get<InstanceRolePolicies>(),
                get<AwsErrorHandler>(),
            )
        }

        // Provide ObjectStore implementation (S3) as singleton
        single<ObjectStore> {
            S3ObjectStore(
                get<S3Client>(),
                get<EventBus>(),
            )
        }

        // Provide SparkService implementation (EMR) as singleton
        single<SparkService> {
            EMRSparkService(
                get<EmrClient>(),
                get<ObjectStore>(),
                get<ClusterStateManager>(),
                get<LokiQueryService>(),
                get<EventBus>(),
                logsDir = get<Context>().workspaceFile(Constants.EMR.LOCAL_LOGS_DIR).toPath(),
            )
        }

        // Provide EMRService as singleton
        single {
            EMRService(
                get<EmrClient>(),
                get<EventBus>(),
            )
        }

        // Provide OpenSearchService as singleton
        single {
            OpenSearchService(
                get<OpenSearchClient>(),
                get<AWS>(),
                get<EventBus>(),
            )
        }

        // Provide AwsS3BucketService as singleton
        single { AwsS3BucketService(get<AWS>()) }

        // Provide AMIResolver as singleton
        single<AMIResolver> {
            DefaultAMIResolver(get<AMIService>())
        }

        // Provide InstanceSpecFactory as singleton
        single<InstanceSpecFactory> { DefaultInstanceSpecFactory() }

        // Turns the nodes' ECR IAM permission into registry credentials a pod can use
        single<ImagePullSecretService> { EcrPullSecretService(get(), get()) }

        // Creates the EC2 key pair profile setup needs; takes the caller-built Ec2Client
        factory { AwsKeyPairService(get(), get(), get<AwsErrorHandler>()) }

        single { CompactorIam(get()) }
        single { CompactorNetwork(get(), get(), get(), get()) }
        single { ClusterCensus(get(), get()) }
        single<CompactorService> { DefaultCompactorService(get(), get(), get(), get(), get(), get()) }
        factory { BucketRegion(get()) }
        factory { AccountBucketSetup(get(), get(), get(), get(), get(), get()) }
    }
