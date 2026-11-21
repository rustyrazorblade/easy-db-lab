package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorAgentJarFile
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContractSource
import com.rustyrazorblade.easydblab.configuration.environment.ToolingForm
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardTreeWriter
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.kubestatemetrics.KubeStateMetricsManifestBuilder
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.JournaldOtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelHostCollectorManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.registry.RegistryManifestBuilder
import com.rustyrazorblade.easydblab.configuration.s3manager.S3ManagerManifestBuilder
import com.rustyrazorblade.easydblab.configuration.sidecar.SidecarManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.yace.YaceManifestBuilder
import com.rustyrazorblade.easydblab.driver.CqlSessionFactory
import com.rustyrazorblade.easydblab.driver.DefaultCqlSessionFactory
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kubernetes.KubeApiLocator
import com.rustyrazorblade.easydblab.network.SocketTcpReachabilityProbe
import com.rustyrazorblade.easydblab.network.TcpReachabilityProbe
import com.rustyrazorblade.easydblab.providers.aws.AccountIdentity
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.providers.docker.DockerClientProvider
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.proxy.LoopbackPortSelector
import com.rustyrazorblade.easydblab.proxy.ProxyAvailability
import com.rustyrazorblade.easydblab.proxy.ProxyPreflight
import com.rustyrazorblade.easydblab.server.ClusterStatusCollector
import com.rustyrazorblade.easydblab.services.CompactorAgentInstaller
import com.rustyrazorblade.easydblab.services.access.CassandraPodForward
import com.rustyrazorblade.easydblab.services.access.Fabric8PodForwarder
import com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService
import com.rustyrazorblade.easydblab.services.aws.BucketRegion
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.EMRProvisioningService
import com.rustyrazorblade.easydblab.services.aws.LaunchCapacityPreflight
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import com.rustyrazorblade.easydblab.services.bringup.aws.Ec2BringUpPorts
import com.rustyrazorblade.easydblab.services.bringup.aws.Ec2InstanceStateWatcher
import com.rustyrazorblade.easydblab.services.bringup.aws.Ec2K3sBringUp
import com.rustyrazorblade.easydblab.services.bringup.k3s.ControlPlaneConnector
import com.rustyrazorblade.easydblab.services.bringup.k3s.ControlPlaneTools
import com.rustyrazorblade.easydblab.services.bringup.k3s.Fabric8ControlPlaneConnector
import com.rustyrazorblade.easydblab.services.bringup.k3s.K3sBringUpSteps
import com.rustyrazorblade.easydblab.services.cassandraversions.AxonOpsAgents
import com.rustyrazorblade.easydblab.services.cassandraversions.CassandraInstaller
import com.rustyrazorblade.easydblab.services.cassandraversions.CassandraNodeRestorer
import com.rustyrazorblade.easydblab.services.cassandraversions.CassandraVersionResolver
import com.rustyrazorblade.easydblab.services.cassandraversions.DefaultVersionSources
import com.rustyrazorblade.easydblab.services.cassandraversions.VersionSources
import com.rustyrazorblade.easydblab.services.documents.DefaultDocumentService
import com.rustyrazorblade.easydblab.services.documents.DocumentService
import com.rustyrazorblade.easydblab.services.stress.StressTargetResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import java.nio.file.Path

private val servicesModuleLog = KotlinLogging.logger {}

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
        factoryOf(::DefaultCassandraProfilingService) bind CassandraProfilingService::class
        factory { NodeTuningFiles() }
        factory { NodeSetupService(get(), get(), get(), get<Context>().workingDirectory) }
        // Singleton: CiliumService records the install window on it during K3s bring-up and `up`
        // posts the annotations later, after Grafana is deployed, so the state must be shared.
        single { CiliumInstallAnnotator(get(), get()) }
        single { LokiPushClient(get()) }
        single<AnnotationMirror> { DefaultAnnotationMirror(get(), get<GrafanaClient>(), get(), get()) }
        factory<CiliumService> { DefaultCiliumService(get(), get(), get()) }
        factory<CiliumInspectionService> { DefaultCiliumInspectionService(get()) }
        factory { CiliumNodeImageCheck(get()) }
        factory { KitStorageResolver(get(), get()) }
        // Run by `init` on a cluster the user brings; the nodes easy-db-lab builds need no probe.
        factory { ClientNetworkProbe(get()) }
        // Where helm and kubectl run follows the recorded environment: over SSH on the control node, or
        // locally through the workspace bin/ wrappers. A factory, resolved from state.json each time,
        // so an `init` inside the REPL or the server switches it for the next command. The SSH form's
        // directory holds its local staging copy of stdin; it is injected because the JVM caches
        // java.io.tmpdir at startup.
        factory<ClusterTooling> {
            val form = get<ClusterContractSource>().current().toolingForm
            servicesModuleLog.debug { "Cluster tooling form: $form" }
            when (form) {
                ToolingForm.Ssh -> SshClusterTooling(get(), get(), Path.of(System.getProperty("java.io.tmpdir")))
                ToolingForm.Local -> LocalClusterTooling(get<Context>().workingDirectory, get())
            }
        }
        factory<HelmService> { DefaultHelmService(get()) }
        factory<KubectlService> { DefaultKubectlService(get()) }
        // Explicit factory (not factoryOf) so the daemonStartupDelay constructor default applies
        // instead of Koin trying to resolve a Duration binding.
        factory<TailscaleService> { DefaultTailscaleService(get(), get()) }

        // Reads the Tailscale client on the developer's own machine. Explicit factory so the
        // constructor defaults (the real CLI runner, the status timeout) apply.
        factory<LocalTailscaleClient> { DefaultLocalTailscaleClient() }
        factory { ProvisioningPreflight(get(), get(), get(), lazy { get<User>() }) }
        factory<LocalSsmTooling> { DefaultLocalSsmTooling() }

        // Proves this machine has a route to the cluster's private network before `up` relies
        // on one. Short timeout: this is a fail-fast check, not a wait-for-ready loop.
        factory<TcpReachabilityProbe> { SocketTcpReachabilityProbe(Constants.Tailscale.REACHABILITY_TIMEOUT_MS) }

        // CQL session factory and service - singleton for session caching in REPL/Server mode
        singleOf(::DefaultCqlSessionFactory) bind CqlSessionFactory::class
        single<CqlSessionService> {
            val workspace = get<Context>().workingDirectory
            val clients = get<K8sClientProvider>()
            DefaultCqlSessionService(get(), get(), get(), get()) {
                val target = CassandraPodTarget.inWorkspace(workspace)
                SingleEndpointCql(
                    forward =
                        CassandraPodForward(
                            pod = target.pod,
                            forwarder = Fabric8PodForwarder(clients::createClient),
                            localPorts = LoopbackPortSelector()::select,
                        ),
                    datacenter = target.datacenter,
                )
            }
        }

        factoryOf(::EC2RegistryService) bind RegistryService::class
        factoryOf(::DefaultK3sService) bind K3sService::class
        factoryOf(::DefaultK3sAgentService) bind K3sAgentService::class
        single<KubeApiLocator> { WorkspaceKubeApiLocator(get(), get<Context>().workingDirectory) }
        single { K8sClientProvider(get(), get()) }
        factory { PodNetworkTuning(get(), Fabric8PodSysctlProbe(get()), get()) }
        factoryOf(::DefaultK8sService).binds(
            arrayOf(
                K8sService::class,
                K8sManifestOperations::class,
                K8sJobOperations::class,
                K8sNamespaceOperations::class,
                K8sStorageOperations::class,
                K8sPodOperations::class,
                K8sReadOperations::class,
            ),
        )
        factoryOf(::RoleNodes)
        factoryOf(::BeylaManifestBuilder)
        factoryOf(::EbpfExporterManifestBuilder)
        // One classpath scan per process: the set of dashboard files cannot change at runtime.
        single { GrafanaDashboardCatalog.discover() }
        factoryOf(::GrafanaDashboardTreeWriter)
        factoryOf(::GrafanaManifestBuilder)
        factoryOf(::JournaldOtelManifestBuilder)
        factoryOf(::OtelManifestBuilder)
        factoryOf(::OtelHostCollectorManifestBuilder)
        factoryOf(::RegistryManifestBuilder)
        factoryOf(::S3ManagerManifestBuilder)
        // Explicit factories, not factoryOf: factoryOf resolves every constructor parameter from
        // Koin and ignores Kotlin defaults, so it would look up the defaulted StoreBlockRenderer.
        factory { PyroscopeManifestBuilder(get()) }
        factory { TempoManifestBuilder(get()) }
        factory { MimirManifestBuilder(get()) }
        factory { LokiManifestBuilder(get()) }
        factoryOf(::YaceManifestBuilder)
        factoryOf(::KubeStateMetricsManifestBuilder)
        factory { CompactorManifestBuilder(get()) }
        factoryOf(::CompactorAgentInstaller)
        // The account compactor stage of every observability deploy: the jar this CLI shipped with, and
        // the account bucket's region, which the compactors sign for.
        factory {
            val bucketRegion = get<BucketRegion>()
            CompactorStage(get(), get(), bucketRegion::of, CompactorAgentJarFile::installed)
        }
        factoryOf(::DefaultGrafanaDeployService) bind GrafanaDeployService::class
        // GrafanaClient reaches Grafana through the service-access seam, with the route on its own client.
        factoryOf(::DefaultGrafanaClient) bind GrafanaClient::class
        factory { DashboardInstallContextFactory(get()) }
        factory { TemplateService(get(), get()) }
        factoryOf(::InstallTemplateResolver)
        factoryOf(::KitProcessEnvironment)
        factoryOf(::KitStorageProvisioner)
        factoryOf(::WorkloadStepExecutor)
        factory<KitHookExecutor> { DefaultKitHookExecutor(get(), get(), get(), get()) }
        factory { KitWorkloadProbe(get(), get()) }
        factory<KitEndpointResolver> { DefaultKitEndpointResolver(get()) }
        factoryOf(::DefaultOtelSyncService) bind OtelSyncService::class
        factory { ConfigChangeReport(get<K8sNamespaceOperations>(), get()) }
        factory { TenantDirectory(get()) }
        factoryOf(::ObservabilityDeployInputs)
        factoryOf(::CoreCollectorManifests)
        factoryOf(::CollectorAgentManifests)
        factoryOf(::BackendManifests)
        factoryOf(::ObservabilityStageManifests)
        factoryOf(::GrafanaStage)
        factory<DocumentService> { DefaultDocumentService(get()) }
        factoryOf(::DefaultObservabilityStackService) bind ObservabilityStackService::class
        factoryOf(::DefaultMetricsRegistryService) bind MetricsRegistryService::class
        factory<GrafanaAnnotationBackupService> {
            DefaultGrafanaAnnotationBackupService(get(), get(), get())
        }
        // The pre-teardown save `down` runs: annotation mirror and collector stop, then
        // the Loki and Mimir flushes, the Tempo drain, the profiles report and the annotations backup
        // in parallel.
        factory<BackendWorkloads> { K8sBackendWorkloads(get()) }
        factory<TelemetrySenders> { K8sTelemetrySenders(get()) }
        factory { LokiTailFlush(get(), get()) }
        factory { MimirTailFlush(get(), get()) }
        factory { TempoTailFlush(get(), get()) }
        factory<TeardownFlushService> {
            DefaultTeardownFlushService
                .builder()
                .lokiFlush(get<LokiTailFlush>())
                .mimirFlush(get<MimirTailFlush>())
                .tempoDrain(get<TempoTailFlush>())
                .telemetrySenders(get())
                .annotationMirror(get())
                .annotationBackupService(get())
                .eventBus(get())
                .build()
        }
        factory<TeardownBackupService> { DefaultTeardownBackupService(get(), get()) }
        single<ObservabilityHttp> {
            val clusterStateManager = get<ClusterStateManager>()
            DefaultObservabilityHttp(get(), { clusterStateManager.load().tenant() }, get())
        }
        factory { TailnetCleanup(get(), lazy { get<User>() }, get()) }
        factory { TeardownPlanner(get()) }
        singleOf(::DefaultLokiQueryService) bind LokiQueryService::class
        factoryOf(::SidecarManifestBuilder)
        factoryOf(::DefaultSidecarService) bind SidecarService::class

        // Explicit single (not singleOf) so the jobPollInterval constructor default applies
        // instead of Koin trying to resolve a Duration binding.
        single<StressJobService> { DefaultStressJobService(get(), get(), get(), get()) }
        factory { StressTargetResolver() }
        // Explicit single (not singleOf) so the maxConcurrency constructor default applies
        // instead of Koin trying to resolve an Int binding.
        single { HostOperationsService(get()) }

        // Builds a Cassandra branch checkout on this machine
        singleOf(::CassandraBuildService)

        // The profile's published Cassandra builds, stored in the account bucket
        single {
            CassandraBuildCatalog(
                get<ObjectStore>(),
                get<AwsS3BucketService>(),
                get<User>(),
            )
        }

        // The Cassandra version sources and the resolver that turns a name into one install
        single<VersionSources> {
            val context = get<Context>()
            DefaultVersionSources(
                profileDir = context.profileDir,
                profileBuildDir = context.cassandraVersionsExtra,
                catalog = get(),
                bucketService = get(),
                user = get(),
            )
        }
        single { CassandraVersionResolver(get()) }
        // The one install path of cassandra install, cassandra use and the node restorer
        single { CassandraInstaller(get(), get(), get(), get()) }
        single { CassandraNodeRestorer(get(), get(), get()) }
        single { AxonOpsAgents(get(), get(), get()) }

        // Kit command scanner — discovers @KitCommand-annotated classes across all JARs
        singleOf(::DefaultKitCommandScanner) bind KitCommandScanner::class

        // Help topic discovery — scans packaged markdown resources for the `help` command
        single<HelpTopicService> { DefaultHelpTopicService() }

        // Kit source registry — persists additional kit parent directories in kit-sources.yaml
        singleOf(::KitSourcesProvider)

        // Kit discovery — the single source of truth for which workspace directories are kits
        singleOf(::WorkspaceKitScanner)

        // The status facts `status` prints and the status server caches; it resolves its own services from Koin
        factory { ClusterStatusCollector() }

        // Cluster configuration service for writing config files
        factoryOf(::DefaultClusterConfigurationService) bind ClusterConfigurationService::class

        // Cluster provisioning service for parallel instance creation
        single<ClusterProvisioningService> {
            DefaultClusterProvisioningService(
                get<EC2InstanceService>(),
                get<EMRProvisioningService>(),
                get<OpenSearchService>(),
                get<AccountIdentity>(),
                get<User>(),
                get<EventBus>(),
            )
        }

        // K3s cluster orchestration service
        single<K3sClusterService> {
            DefaultK3sClusterService(
                get<K3sService>(),
                get<K3sAgentService>(),
                get<EventBus>(),
                get<ClusterBackupService>(),
            )
        }

        // `up`'s per-node bring-up (design D12): the K3s steps over SSH and fabric8, and the EC2
        // instance tracker and fleet. Each `up` gets a new tracker from the factory lambda.
        single<ControlPlaneConnector> { Fabric8ControlPlaneConnector(get()) }
        factory {
            K3sBringUpSteps(
                get<RemoteOperationsService>(),
                get<NodeSetupService>(),
                get<CiliumNodeImageCheck>(),
                get<RegistryService>(),
                get<K3sClusterService>(),
                ControlPlaneTools(get<K8sManifestOperations>(), get<CiliumService>(), get<ControlPlaneConnector>()),
            )
        }
        factory<Ec2BringUpPorts> {
            val ec2 = get<EC2InstanceService>()
            Ec2K3sBringUp(ec2, get<LaunchCapacityPreflight>(), { Ec2InstanceStateWatcher(ec2) }, get<K3sBringUpSteps>())
        }

        // Cluster backup service for S3 backup/restore of cluster configuration files
        single<ClusterBackupService> {
            DefaultClusterBackupService(
                get<ObjectStore>(),
                get<EventBus>(),
            )
        }

        // Unified backup/restore service coordinating VPC lookup and S3 file operations
        single<BackupRestoreService> {
            DefaultBackupRestoreService(
                get<VpcService>(),
                get<ClusterBackupService>(),
                get<ClusterStateManager>(),
                get<EventBus>(),
                get<ClusterConfigurationService>(),
                get<User>(),
            )
        }

        // Resolves the developer's public IP for scoping security-group SSH ingress to a /32
        single<ExternalIpService> { DefaultExternalIpService() }

        // Reports a failed command as one event; getAll() collects every provider's error handler
        single { CommandFailureReporter(get(), getAll()) }

        // Command executor for scheduling and executing commands with full lifecycle
        // Note: BackupRestoreService is lazily injected in DefaultCommandExecutor to avoid
        // triggering the AWS dependency chain during profile setup (when settings.yaml doesn't exist yet)
        single<CommandExecutor> {
            DefaultCommandExecutor(
                get<Context>(),
                get<ClusterStateManager>(),
                RequirementCheckDeps(
                    get<UserConfigProvider>(),
                    get<DockerClientProvider>(),
                    EnvironmentRequirements(get()),
                ),
                get<ResourceManager>(),
                get<EventBus>(),
                get<ProxyPreflight>(),
                get<ProxyAvailability>(),
                get<ProfileSetupCommandProvider>(),
            )
        }
    }
