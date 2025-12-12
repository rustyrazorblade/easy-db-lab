package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.providers.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.providers.aws.EMRService
import com.rustyrazorblade.easydblab.providers.aws.OpenSearchService
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Koin module for registering business services.
 *
 * This module provides service-layer components that encapsulate
 * business logic and orchestrate operations across multiple infrastructure
 * components (SSH, Docker, AWS, etc.).
 */
val servicesModule =
    module {
        factoryOf(::DefaultCassandraService) bind CassandraService::class
        factoryOf(::EC2RegistryService) bind RegistryService::class
        factoryOf(::DefaultK3sService) bind K3sService::class
        factoryOf(::DefaultK3sAgentService) bind K3sAgentService::class
        factoryOf(::DefaultK8sService) bind K8sService::class
        factoryOf(::DefaultSidecarService) bind SidecarService::class
        factoryOf(::DefaultStressJobService) bind StressJobService::class
        singleOf(::HostOperationsService)

        // Cluster configuration service for writing config files
        factoryOf(::DefaultClusterConfigurationService) bind ClusterConfigurationService::class

        // Cluster provisioning service for parallel instance creation
        single<ClusterProvisioningService> {
            DefaultClusterProvisioningService(
                get<EC2InstanceService>(),
                get<EMRService>(),
                get<OpenSearchService>(),
                get<OutputHandler>(),
                get<AWS>(),
                get<User>(),
            )
        }

        // K3s cluster orchestration service
        single<K3sClusterService> {
            DefaultK3sClusterService(
                get<K3sService>(),
                get<K3sAgentService>(),
                get<OutputHandler>(),
            )
        }

        // State reconstruction service for recovering state from AWS resources
        single<StateReconstructionService> {
            DefaultStateReconstructionService(
                get<VpcService>(),
                get<EC2InstanceService>(),
                get<AWS>(),
            )
        }

        // Cluster backup service for S3 backup/restore of cluster configuration files
        single<ClusterBackupService> {
            DefaultClusterBackupService(
                get<ObjectStore>(),
                get<OutputHandler>(),
            )
        }

        // Unified backup/restore service coordinating state reconstruction and file operations
        single<BackupRestoreService> {
            DefaultBackupRestoreService(
                get<StateReconstructionService>(),
                get<ClusterBackupService>(),
                get<ClusterStateManager>(),
                get<OutputHandler>(),
            )
        }
    }
