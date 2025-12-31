package com.rustyrazorblade.easydblab.commands

import com.github.ajalt.mordant.TermColors
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.TriggerBackup
import com.rustyrazorblade.easydblab.commands.cassandra.WriteConfig
import com.rustyrazorblade.easydblab.commands.k8.K8Apply
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.providers.aws.AMIResolver
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.providers.aws.AwsInfrastructureService
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredInstance
import com.rustyrazorblade.easydblab.providers.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.providers.aws.InstanceSpecFactory
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import com.rustyrazorblade.easydblab.providers.aws.S3ObjectStore
import com.rustyrazorblade.easydblab.providers.aws.SQSService
import com.rustyrazorblade.easydblab.providers.aws.VpcNetworkingConfig
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.services.ClusterConfigurationService
import com.rustyrazorblade.easydblab.services.ClusterProvisioningService
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.InstanceProvisioningConfig
import com.rustyrazorblade.easydblab.services.K3sClusterConfig
import com.rustyrazorblade.easydblab.services.K3sClusterService
import com.rustyrazorblade.easydblab.services.ObjectStore
import com.rustyrazorblade.easydblab.services.OptionalServicesConfig
import com.rustyrazorblade.easydblab.services.RegistryService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import org.koin.core.component.inject
import picocli.CommandLine
import java.io.File
import java.net.URL
import java.time.Duration

/**
 * Provisions and configures the complete cluster infrastructure.
 *
 * This command orchestrates parallel creation of:
 * - EC2 instances (Cassandra, Stress, Control nodes)
 * - EMR Spark cluster (if enabled)
 * - OpenSearch domain (if enabled)
 *
 * After provisioning, it configures K3s on all nodes and applies Kubernetes manifests.
 * State is persisted incrementally as each resource type completes.
 *
 * @see Init for cluster initialization (must be run first)
 * @see Down for tearing down infrastructure
 */
@McpCommand
@RequireProfileSetup
@TriggerBackup
@CommandLine.Command(
    name = "up",
    description = ["Starts instances"],
)
class Up : PicoBaseCommand() {
    private val userConfig: User by inject()
    private val aws: AWS by inject()
    private val vpcService: VpcService by inject()
    private val awsInfrastructureService: AwsInfrastructureService by inject()
    private val ec2InstanceService: EC2InstanceService by inject()
    private val hostOperationsService: HostOperationsService by inject()
    private val amiResolver: AMIResolver by inject()
    private val instanceSpecFactory: InstanceSpecFactory by inject()
    private val clusterProvisioningService: ClusterProvisioningService by inject()
    private val clusterConfigurationService: ClusterConfigurationService by inject()
    private val k3sClusterService: K3sClusterService by inject()
    private val registryService: RegistryService by inject()
    private val commandExecutor: CommandExecutor by inject()
    private val sqsService: SQSService by inject()
    private val objectStore: ObjectStore by inject()

    // Working copy loaded during execute() - modified and saved
    private lateinit var workingState: ClusterState

    companion object {
        private val log = KotlinLogging.logger {}
        private val SSH_STARTUP_DELAY = Duration.ofSeconds(5)
        private const val BUCKET_UUID_LENGTH = 8

        /**
         * Gets the external IP address of the machine running easy-db-lab.
         * Used to restrict SSH access to the security group.
         */
        private fun getExternalIpAddress(): String = URL("https://api.ipify.org/").readText()
    }

    // Lock for synchronizing access to workingState from parallel threads
    private val stateLock = Any()

    @CommandLine.Option(names = ["--no-setup", "-n"])
    var noSetup = false

    @CommandLine.Mixin
    var hosts = HostsMixin()

    override fun execute() {
        workingState = clusterStateManager.load()
        val initConfig =
            workingState.initConfig
                ?: error("No init config found. Please run 'easy-db-lab init' first.")

        createS3BucketIfNeeded(initConfig)
        createSqsQueueIfNeeded()
        reapplyS3Policy()
        provisionInfrastructure(initConfig)
        writeConfigurationFiles()
        commandExecutor.execute { WriteConfig() }
        waitForSshAndDownloadVersions()
        setupInstancesIfNeeded()
    }

    /**
     * Re-applies the S3Access inline policy to ensure existing roles have the latest permissions.
     * This is idempotent - PutRolePolicy overwrites existing policies with the same name.
     * Uses a wildcard policy that grants access to all easy-db-lab-* buckets.
     */
    private fun reapplyS3Policy() {
        outputHandler.handleMessage("Ensuring IAM policies are up to date...")
        runCatching {
            aws.attachS3Policy(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        }.onFailure { e ->
            log.warn(e) { "Failed to re-apply S3 policy (cluster may still work if policy exists)" }
        }.onSuccess {
            log.debug { "S3 policy re-applied successfully" }
        }
    }

    /**
     * Creates the S3 bucket for this environment if it doesn't already exist.
     * Each environment gets its own dedicated bucket for isolation.
     * The bucket is tagged with the ClusterId to enable state reconstruction.
     */
    private fun createS3BucketIfNeeded(initConfig: InitConfig) {
        if (!workingState.s3Bucket.isNullOrBlank()) {
            log.debug { "S3 bucket already configured: ${workingState.s3Bucket}" }
            return
        }

        val shortUuid = workingState.clusterId.take(BUCKET_UUID_LENGTH)
        val bucketName = "easy-db-lab-${initConfig.name}-$shortUuid"

        outputHandler.handleMessage("Creating S3 bucket: $bucketName")
        aws.createS3Bucket(bucketName)
        aws.putS3BucketPolicy(bucketName)
        aws.tagS3Bucket(bucketName, mapOf("ClusterId" to workingState.clusterId) + initConfig.tags)

        workingState.s3Bucket = bucketName
        clusterStateManager.save(workingState)

        outputHandler.handleMessage("S3 bucket created and configured: $bucketName")
    }

    /**
     * Creates the SQS queue for log ingestion if it doesn't already exist.
     * Also configures S3 bucket notifications to send EMR log events to the queue.
     */
    private fun createSqsQueueIfNeeded() {
        if (!workingState.sqsQueueUrl.isNullOrBlank()) {
            log.debug { "SQS queue already configured: ${workingState.sqsQueueUrl}" }
            return
        }

        val s3Bucket =
            workingState.s3Bucket
                ?: error("S3 bucket not configured. This is required for log ingestion.")

        // Create SQS queue
        val bucketArn = "arn:aws:s3:::$s3Bucket"
        val queueInfo =
            sqsService
                .createLogIngestQueue(workingState.clusterId, bucketArn)
                .getOrThrow()

        // Configure S3 bucket notifications
        val s3ObjectStore =
            objectStore as? S3ObjectStore
                ?: error("ObjectStore is not S3ObjectStore. S3 is required for log ingestion.")

        s3ObjectStore
            .configureEMRLogNotifications(s3Bucket, queueInfo.queueArn)
            .getOrThrow()

        // Verify the notification was configured correctly
        validateLogPipeline(s3ObjectStore, s3Bucket, queueInfo.queueArn)

        // Update state
        workingState.updateSqsQueue(queueInfo.queueUrl, queueInfo.queueArn)
        clusterStateManager.save(workingState)

        outputHandler.handleMessage("SQS queue configured for log ingestion: ${queueInfo.queueUrl}")
    }

    /**
     * Validates that the log ingestion pipeline is correctly configured.
     * Throws an exception if S3 bucket notifications are not set up to send to the SQS queue.
     */
    private fun validateLogPipeline(
        s3ObjectStore: S3ObjectStore,
        bucket: String,
        expectedQueueArn: String,
    ) {
        outputHandler.handleMessage("Validating log ingestion pipeline...")

        val notifConfig = s3ObjectStore.getBucketNotificationConfiguration(bucket)
        val hasEMRNotification =
            notifConfig.queueConfigurations().any { it.queueArn() == expectedQueueArn }

        if (!hasEMRNotification) {
            throw IllegalStateException(
                "S3 bucket notifications are not configured correctly. " +
                    "EMR logs at s3://$bucket/${Constants.EMR.S3_LOG_PREFIX} will not trigger SQS notifications.",
            )
        }

        outputHandler.handleMessage("✓ S3 → SQS notifications configured correctly")
    }

    /**
     * Creates a new VPC or validates an existing one.
     *
     * If no VPC ID is stored in state, creates a new VPC with appropriate tags.
     * If a VPC ID exists, validates it still exists in AWS.
     *
     * @param initConfig Configuration containing cluster name and tags
     * @return The VPC ID to use for infrastructure
     */
    private fun createOrValidateVpc(initConfig: InitConfig): String {
        val existingVpcId = workingState.vpcId

        if (existingVpcId != null) {
            // Validate existing VPC still exists
            val vpcName = vpcService.getVpcName(existingVpcId)
            if (vpcName == null) {
                error(
                    "VPC $existingVpcId not found in AWS. It may have been deleted. " +
                        "Please run 'easy-db-lab clean' and 'easy-db-lab init' to recreate.",
                )
            }
            log.info { "Using existing VPC: $existingVpcId ($vpcName)" }
            return existingVpcId
        }

        // Create new VPC
        outputHandler.handleMessage("Creating VPC for cluster: ${initConfig.name}")
        val vpcTags =
            mapOf(
                Constants.Vpc.TAG_KEY to Constants.Vpc.TAG_VALUE,
                "ClusterId" to workingState.clusterId,
            ) + initConfig.tags

        val vpcId = vpcService.createVpc(initConfig.name, Constants.Vpc.DEFAULT_CIDR, vpcTags)
        outputHandler.handleMessage("VPC created: $vpcId")

        // Save VPC ID to state
        workingState.vpcId = vpcId
        clusterStateManager.save(workingState)

        return vpcId
    }

    /**
     * Provisions all infrastructure resources in parallel.
     *
     * Creates EC2 instances, EMR clusters, and OpenSearch domains concurrently.
     * Each resource type updates cluster state atomically when complete.
     */
    private fun provisionInfrastructure(initConfig: InitConfig) {
        outputHandler.handleMessage("Provisioning infrastructure...")

        // Create VPC if needed, or validate existing one
        val vpcId = createOrValidateVpc(initConfig)

        // Set up VPC networking (subnets, security groups, internet gateway)
        val availabilityZones =
            if (initConfig.azs.isNotEmpty()) {
                initConfig.azs
            } else {
                listOf("a", "b", "c")
            }
        val vpcNetworkingConfig =
            VpcNetworkingConfig(
                vpcId = vpcId,
                clusterName = initConfig.name,
                clusterId = workingState.clusterId,
                region = initConfig.region,
                availabilityZones = availabilityZones,
                isOpen = initConfig.open,
                tags = initConfig.tags,
            )
        val vpcInfra = awsInfrastructureService.setupVpcNetworking(vpcNetworkingConfig) { getExternalIpAddress() }
        val subnetIds = vpcInfra.subnetIds
        val securityGroupId = vpcInfra.securityGroupId
        val igwId = vpcInfra.internetGatewayId

        // Resolve AMI ID
        val amiId =
            amiResolver.resolveAmiId(initConfig.ami, initConfig.arch).getOrElse { error ->
                error(error.message ?: "Failed to resolve AMI")
            }

        val baseTags =
            mapOf(
                "easy_cass_lab" to "1",
                "ClusterId" to workingState.clusterId,
            ) + initConfig.tags

        // Discover existing instances and create specs
        val existingInstances = ec2InstanceService.findInstancesByClusterId(workingState.clusterId)
        logExistingInstances(existingInstances)

        // Convert existing instances to ClusterHosts
        val existingHosts =
            existingInstances.mapValues { (_, instances) ->
                instances.map { it.toClusterHost() }
            }

        // Create instance specs using factory
        val instanceSpecs = instanceSpecFactory.createInstanceSpecs(initConfig, existingInstances)

        // Configure provisioning
        val instanceConfig =
            InstanceProvisioningConfig(
                specs = instanceSpecs,
                amiId = amiId,
                securityGroupId = securityGroupId,
                subnetIds = subnetIds,
                tags = baseTags,
                clusterName = initConfig.name,
                userConfig = userConfig,
            )

        val servicesConfig =
            OptionalServicesConfig(
                initConfig = initConfig,
                subnetId = subnetIds.first(),
                securityGroupId = securityGroupId,
                tags = baseTags,
                clusterState = workingState,
            )

        // Ensure OpenSearch service-linked role exists if needed
        if (initConfig.opensearchEnabled) {
            aws.ensureOpenSearchServiceLinkedRole()
        }

        // Provision all infrastructure in parallel
        val result =
            clusterProvisioningService.provisionAll(
                instanceConfig = instanceConfig,
                servicesConfig = servicesConfig,
                existingHosts = existingHosts,
                onHostsCreated = { serverType, hosts ->
                    synchronized(stateLock) {
                        val allHosts = workingState.hosts.toMutableMap()
                        allHosts[serverType] = (allHosts[serverType] ?: emptyList()) + hosts
                        workingState.updateHosts(allHosts)
                        clusterStateManager.save(workingState)
                    }
                },
                onEmrCreated = { emrState ->
                    synchronized(stateLock) {
                        workingState.updateEmrCluster(emrState)
                        clusterStateManager.save(workingState)
                    }
                    outputHandler.handleMessage("EMR cluster ready: ${emrState.masterPublicDns}")
                },
                onOpenSearchCreated = { osState ->
                    synchronized(stateLock) {
                        workingState.updateOpenSearchDomain(osState)
                        clusterStateManager.save(workingState)
                    }
                    outputHandler.handleMessage("OpenSearch domain ready: https://${osState.endpoint}")
                    osState.dashboardsEndpoint?.let { outputHandler.handleMessage("Dashboards: $it") }
                },
            )

        // Report any failures
        if (result.errors.isNotEmpty()) {
            outputHandler.handleMessage("\nInfrastructure creation had failures:")
            result.errors.forEach { (resource, error) ->
                outputHandler.handleMessage("  - $resource: ${error.message}")
            }
            error(
                "Failed to create ${result.errors.size} infrastructure resource(s): " +
                    result.errors.keys.joinToString(", "),
            )
        }

        // Update infrastructure state and mark as up
        synchronized(stateLock) {
            workingState.updateInfrastructure(
                InfrastructureState(
                    vpcId = vpcId,
                    subnetIds = subnetIds,
                    securityGroupId = securityGroupId,
                    internetGatewayId = igwId,
                ),
            )
            workingState.markInfrastructureUp()
            clusterStateManager.save(workingState)
        }

        printProvisioningSuccessMessage()
        outputHandler.handleMessage("Cluster state updated: ${result.hosts.values.flatten().size} hosts tracked")
    }

    private fun logExistingInstances(existingInstances: Map<ServerType, List<DiscoveredInstance>>) {
        if (existingInstances.values.flatten().isNotEmpty()) {
            val cassandra = existingInstances[ServerType.Cassandra]?.size ?: 0
            val stress = existingInstances[ServerType.Stress]?.size ?: 0
            val control = existingInstances[ServerType.Control]?.size ?: 0
            outputHandler.handleMessage(
                "Discovered existing instances: Cassandra=$cassandra, Stress=$stress, Control=$control",
            )
        }
    }

    private fun printProvisioningSuccessMessage() {
        with(TermColors()) {
            outputHandler.handleMessage(
                "Instances have been provisioned.\n\n" +
                    "Use " + green("easy-db-lab list") + " to see all available versions\n\n" +
                    "Then use " + green("easy-db-lab use <version>") +
                    " to use a specific version of Cassandra.\n",
            )
            outputHandler.handleMessage("Writing ssh config file to sshConfig.")
            outputHandler.handleMessage(
                "The following alias will allow you to easily work with the cluster:\n\n" +
                    green("source env.sh") + "\n",
            )
            outputHandler.handleMessage(
                "You can edit " + green("cassandra.patch.yaml") +
                    " with any changes you'd like to see merge in into the remote cassandra.yaml file.",
            )
        }
    }

    /** Writes SSH config, environment files, and AxonOps configuration to the working directory. */
    private fun writeConfigurationFiles() {
        clusterConfigurationService
            .writeAllConfigurationFiles(
                context.workingDirectory.toPath(),
                workingState,
                userConfig,
            ).onFailure { error ->
                log.error(error) { "Failed to write some configuration files" }
            }
    }

    /** Waits for SSH to become available on instances and downloads Cassandra version info. */
    private fun waitForSshAndDownloadVersions() {
        outputHandler.handleMessage("Waiting for SSH to come up..")
        Thread.sleep(SSH_STARTUP_DELAY.toMillis())

        val retryConfig = RetryUtil.createSshConnectionRetryConfig()
        val retry =
            Retry.of("ssh-connection", retryConfig).also {
                it.eventPublisher.onRetry { event ->
                    outputHandler.handleMessage(
                        "SSH still not up yet, waiting... (attempt ${event.numberOfRetryAttempts})",
                    )
                }
            }

        Retry
            .decorateRunnable(retry) {
                hostOperationsService.withHosts(
                    workingState.hosts,
                    ServerType.Cassandra,
                    hosts.hostList,
                ) { clusterHost ->
                    val host = clusterHost.toHost()
                    remoteOps.executeRemotely(host, "echo 1").text
                    val versionsFile = File(context.workingDirectory, "cassandra_versions.yaml")
                    if (!versionsFile.exists()) {
                        remoteOps.download(
                            host,
                            "/etc/cassandra_versions.yaml",
                            versionsFile.toPath(),
                        )
                    }
                }
            }.run()
    }

    /** Runs instance setup, K3s configuration, and optional AxonOps setup unless --no-setup. */
    private fun setupInstancesIfNeeded() {
        if (noSetup) {
            with(TermColors()) {
                outputHandler.handleMessage(
                    "Skipping node setup.  You will need to run " +
                        green("easy-db-lab setup-instance") + " to complete setup",
                )
            }
        } else {
            commandExecutor.execute { SetupInstance() }
            startK3sOnAllNodes()

            if (userConfig.axonOpsKey.isNotBlank() && userConfig.axonOpsOrg.isNotBlank()) {
                outputHandler.handleMessage("Setting up axonops for ${userConfig.axonOpsOrg}")
                commandExecutor.execute { ConfigureAxonOps() }
            }
        }
    }

    /** Starts K3s server on control node and joins Cassandra/Stress nodes as agents. */
    private fun startK3sOnAllNodes() {
        val controlHosts = workingState.hosts[ServerType.Control] ?: emptyList()
        if (controlHosts.isEmpty()) {
            outputHandler.handleError("No control nodes found, skipping K3s setup")
            return
        }

        // Configure registry TLS before K3s starts so registries.yaml is in place
        configureRegistryTls()

        val config =
            K3sClusterConfig(
                controlHost = controlHosts.first(),
                workerHosts =
                    mapOf(
                        ServerType.Cassandra to (workingState.hosts[ServerType.Cassandra] ?: emptyList()),
                        ServerType.Stress to (workingState.hosts[ServerType.Stress] ?: emptyList()),
                    ),
                kubeconfigPath = File(context.workingDirectory, "kubeconfig").toPath(),
                hostFilter = hosts.hostList,
                clusterState = workingState,
            )

        val result = k3sClusterService.setupCluster(config)

        if (!result.isSuccessful) {
            result.errors.forEach { (operation, error) ->
                log.error(error) { "K3s setup failed: $operation" }
            }
        }

        commandExecutor.execute { K8Apply() }
    }

    /**
     * Configures TLS for the container registry.
     *
     * Generates a self-signed certificate on the control node and configures both containerd
     * and K3s registries.yaml on all nodes to trust the registry. This must happen before K3s
     * starts so that K3s picks up the HTTPS registry configuration.
     */
    private fun configureRegistryTls() {
        val controlHosts = workingState.hosts[ServerType.Control] ?: emptyList()
        if (controlHosts.isEmpty()) {
            log.warn { "No control nodes found, skipping registry TLS configuration" }
            return
        }

        val s3Bucket = workingState.s3Bucket
        if (s3Bucket.isNullOrBlank()) {
            log.warn { "S3 bucket not configured, skipping registry TLS configuration" }
            return
        }

        val controlHost = controlHosts.first().toHost()
        val registryHost = controlHost.private

        // Generate cert on control node and upload to S3
        registryService.generateAndUploadCert(controlHost, s3Bucket)

        // Configure containerd on ALL nodes to trust the registry
        val allHosts =
            (workingState.hosts[ServerType.Control] ?: emptyList()) +
                (workingState.hosts[ServerType.Cassandra] ?: emptyList()) +
                (workingState.hosts[ServerType.Stress] ?: emptyList())

        allHosts.forEach { clusterHost ->
            registryService.configureTlsOnNode(clusterHost.toHost(), registryHost, s3Bucket)
        }

        outputHandler.handleMessage("Registry TLS configured on all nodes")
    }
}
