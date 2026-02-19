package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.driver.CqlSessionFactory
import com.rustyrazorblade.easydblab.driver.DefaultCqlSessionFactory
import com.rustyrazorblade.easydblab.observability.TelemetryProvider
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.providers.docker.DockerClientProvider
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.EMRService
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
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
        // Resource manager for centralized cleanup - must be singleton
        singleOf(::DefaultResourceManager) bind ResourceManager::class

        factoryOf(::DefaultCassandraService) bind CassandraService::class
        factoryOf(::DefaultClickHouseConfigService) bind ClickHouseConfigService::class
        factoryOf(::DefaultTailscaleService) bind TailscaleService::class

        // CQL session factory and service - singleton for session caching in REPL/Server mode
        singleOf(::DefaultCqlSessionFactory) bind CqlSessionFactory::class
        singleOf(::DefaultCqlSessionService) bind CqlSessionService::class

        factoryOf(::EC2RegistryService) bind RegistryService::class
        factoryOf(::DefaultK3sService) bind K3sService::class
        factoryOf(::DefaultK3sAgentService) bind K3sAgentService::class
        factoryOf(::DefaultK8sService) bind K8sService::class
        factoryOf(::GrafanaManifestBuilder)
        factoryOf(::DefaultGrafanaDashboardService) bind GrafanaDashboardService::class
        factoryOf(::TemplateService)
        factoryOf(::DefaultVictoriaBackupService) bind VictoriaBackupService::class
        factoryOf(::DefaultVictoriaStreamService) bind VictoriaStreamService::class
        factoryOf(::DefaultSidecarService) bind SidecarService::class
        singleOf(::DefaultStressJobService) bind StressJobService::class
        singleOf(::HostOperationsService)

        // Cluster configuration service for writing config files
        factoryOf(::DefaultClusterConfigurationService) bind ClusterConfigurationService::class

        // EMR provisioning service for Spark cluster creation
        single<EMRProvisioningService> {
            DefaultEMRProvisioningService(
                get<EMRService>(),
                get<OutputHandler>(),
            )
        }

        // Cluster provisioning service for parallel instance creation
        single<ClusterProvisioningService> {
            DefaultClusterProvisioningService(
                get<EC2InstanceService>(),
                get<EMRProvisioningService>(),
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
                get<ClusterBackupService>(),
            )
        }

        // Cluster backup service for S3 backup/restore of cluster configuration files
        single<ClusterBackupService> {
            DefaultClusterBackupService(
                get<ObjectStore>(),
                get<OutputHandler>(),
            )
        }

        // Unified backup/restore service coordinating VPC lookup and S3 file operations
        single<BackupRestoreService> {
            DefaultBackupRestoreService(
                get<VpcService>(),
                get<ClusterBackupService>(),
                get<ClusterStateManager>(),
                get<OutputHandler>(),
            )
        }

        // Command executor for scheduling and executing commands with full lifecycle
        // Note: BackupRestoreService is lazily injected in DefaultCommandExecutor to avoid
        // triggering the AWS dependency chain during setup-profile (when settings.yaml doesn't exist yet)
        single<CommandExecutor> {
            DefaultCommandExecutor(
                get<Context>(),
                get<ClusterStateManager>(),
                get<OutputHandler>(),
                get<UserConfigProvider>(),
                get<DockerClientProvider>(),
                get<ResourceManager>(),
                get<TelemetryProvider>(),
            )
        }
    }
