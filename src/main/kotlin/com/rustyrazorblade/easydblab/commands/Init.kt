package com.rustyrazorblade.easydblab.commands

import com.fasterxml.jackson.annotation.JsonIgnore
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.converters.PicoAZConverter
import com.rustyrazorblade.easydblab.commands.converters.PicoArchConverter
import com.rustyrazorblade.easydblab.commands.mixins.OpenSearchInitMixin
import com.rustyrazorblade.easydblab.commands.mixins.SparkInitMixin
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.network.CidrBlock
import com.rustyrazorblade.easydblab.services.CommandExecutor
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File
import java.time.LocalDate
import kotlin.system.exitProcess

/**
 * Initialize this directory for easy-db-lab.
 *
 * IMPORTANT: This command only sets up local configuration files and state.
 * It does NOT provision any AWS infrastructure (VPC, EC2 instances, etc.).
 *
 * Responsibilities of init:
 * - Create and configure state.json with cluster configuration
 * - Extract resource files (manifests, scripts, etc.)
 * - Validate AWS credentials and configuration
 * - Store user-provided tags for later use during provisioning
 *
 * What init does NOT do:
 * - Create VPCs (done by 'up')
 * - Create EC2 instances (done by 'up')
 * - Create S3 buckets (done by 'up')
 * - Create any other AWS resources
 *
 * To provision infrastructure after init, use:
 * - `easy-db-lab up` to create all AWS resources
 * - Or use `init --up` to combine both steps
 *
 * @see Up for infrastructure provisioning
 * @see Down for tearing down infrastructure
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "init",
    description = ["Initialize this directory for easy-db-lab"],
)
class Init : PicoBaseCommand() {
    private val userConfig: User by inject()
    private val commandExecutor: CommandExecutor by inject()

    companion object {
        private const val DEFAULT_CASSANDRA_INSTANCE_COUNT = 3
        private const val DEFAULT_EBS_SIZE_GB = 256

        @JsonIgnore val log = KotlinLogging.logger {}
    }

    @Option(
        names = ["--db", "--cassandra", "-c"],
        description = ["Legacy alias for --db.count. Number of database instances"],
    )
    var cassandraInstances = DEFAULT_CASSANDRA_INSTANCE_COUNT

    @Option(
        names = ["--db.count"],
        description = ["Number of database instances. Takes precedence over the legacy --db/--cassandra/-c alias"],
    )
    var dbCount: Int? = null

    @Option(
        names = ["--app", "--stress", "-s"],
        description = ["Legacy alias for --app.count. Number of application instances"],
    )
    var stressInstances = 0

    @Option(
        names = ["--app.count"],
        description = ["Number of application instances. Takes precedence over the legacy --app/--stress/-s alias"],
    )
    var appCount: Int? = null

    @Option(
        names = ["--up"],
        description = ["Start instances automatically"],
    )
    var start = false

    @Option(
        names = ["--instance", "-i"],
        description = [
            "Legacy alias for --db.instance-type. Database instance type. " +
                "Set EASY_DB_LAB_INSTANCE_TYPE to set a default.",
        ],
    )
    var instanceType: String = System.getenv("EASY_DB_LAB_INSTANCE_TYPE") ?: "i4i.xlarge"

    @Option(
        names = ["--db.instance-type"],
        description = [
            "Database instance type. Takes precedence over the legacy --instance/-i alias.",
        ],
    )
    var dbInstanceType: String? = null

    @Option(
        names = ["--stress-instance", "-si", "--si"],
        description = [
            "Legacy alias for --app.instance-type. Application (stress) instance type. " +
                "Set EASY_DB_LAB_STRESS_INSTANCE_TYPE to set a default.",
        ],
    )
    var stressInstanceType: String = System.getenv("EASY_DB_LAB_STRESS_INSTANCE_TYPE") ?: "c6id.2xlarge"

    @Option(
        names = ["--app.instance-type"],
        description = [
            "Application (stress) instance type. " +
                "Takes precedence over the legacy --stress-instance/-si alias.",
        ],
    )
    var appInstanceType: String? = null

    @Option(
        names = ["--azs", "--az", "-z"],
        description = ["Limit to specified availability zones"],
        converter = [PicoAZConverter::class],
    )
    var azs: List<String> = listOf()

    @Option(
        names = ["--until"],
        description = ["Specify when the instances can be deleted"],
    )
    var until: String = LocalDate.now().plusDays(1).toString()

    @Option(
        names = ["--ami"],
        description = ["AMI. Set EASY_DB_LAB_AMI to override the default."],
    )
    var ami: String = System.getenv("EASY_DB_LAB_AMI").orEmpty()

    @Option(
        names = ["--open"],
        description = ["Unrestricted SSH access"],
    )
    var open = false

    @Option(
        names = ["--ebs.type"],
        description = ["EBS Volume Type (NONE, gp2, gp3, io1, io2)"],
    )
    var ebsType = "NONE"

    @Option(
        names = ["--ebs.size"],
        description = ["EBS Volume Size (in GB)"],
    )
    var ebsSize = DEFAULT_EBS_SIZE_GB

    @Option(
        names = ["--ebs.iops"],
        description = ["EBS Volume IOPS (note: only applies if '--ebs.type gp3'"],
    )
    var ebsIops = 0

    @Option(
        names = ["--ebs.throughput"],
        description = ["EBS Volume Throughput (note: only applies if '--ebs.type gp3')"],
    )
    var ebsThroughput = 0

    @Option(
        names = ["--ebs.optimized"],
        description = ["Set EBS-Optimized instance (only supported for EBS-optimized instance types"],
    )
    var ebsOptimized = false

    @Parameters(
        description = ["Cluster name"],
        defaultValue = "test",
    )
    var name = "test"

    @Option(
        names = ["--arch", "-a", "--cpu"],
        description = ["CPU architecture"],
        converter = [PicoArchConverter::class],
    )
    var arch: Arch = Arch.AMD64

    @Mixin
    var spark = SparkInitMixin()

    @Mixin
    var opensearch = OpenSearchInitMixin()

    @Option(
        names = ["--tag"],
        description = ["Tag instances (format: key=value, can be repeated)"],
        arity = "1..*",
        split = ",",
    )
    var tags: Map<String, String> = mutableMapOf()

    @Option(
        names = ["--clean"],
        description = ["Clean existing configuration before initializing"],
    )
    var clean = false

    @Option(
        names = ["--no-tailscale"],
        description = ["Disable Tailscale even if credentials are configured in profile"],
    )
    var noTailscale = false

    @Option(
        names = ["--vpc"],
        description = ["Use an existing VPC ID instead of creating a new one"],
    )
    var existingVpcId: String? = null

    @Option(
        names = ["--cidr"],
        description = ["VPC CIDR block (e.g. 10.2.0.0/16). Must be /20 or larger. Omit to auto-select a non-conflicting block."],
    )
    var cidr: String? = null

    @Option(
        names = ["--cilium"],
        description = ["Install Cilium CNI instead of the default Flannel CNI"],
    )
    var cilium = false

    /**
     * Resolved number of database instances: the namespaced `--db.count` when supplied, otherwise
     * the legacy `--db`/`--cassandra`/`-c` alias (or its default). Namespaced always wins.
     */
    @get:JsonIgnore
    val resolvedDbCount: Int get() = dbCount ?: cassandraInstances

    /**
     * Resolved number of application instances: the namespaced `--app.count` when supplied,
     * otherwise the legacy `--app`/`--stress`/`-s` alias (or its default). Namespaced always wins.
     */
    @get:JsonIgnore
    val resolvedAppCount: Int get() = appCount ?: stressInstances

    /**
     * Resolved database instance type: the namespaced `--db.instance-type` when supplied, otherwise
     * the legacy `--instance`/`-i` alias (or its default). Namespaced always wins.
     */
    @get:JsonIgnore
    val resolvedDbInstanceType: String get() = dbInstanceType ?: instanceType

    /**
     * Resolved application instance type: the namespaced `--app.instance-type` when supplied,
     * otherwise the legacy `--stress-instance`/`-si` alias (or its default). Namespaced always wins.
     */
    @get:JsonIgnore
    val resolvedAppInstanceType: String get() = appInstanceType ?: stressInstanceType

    override fun execute() {
        validateParameters()

        if (!clean) {
            checkExistingFiles()
        }

        val clusterState = prepareEnvironment()

        eventBus.emit(Event.Setup.InitializingDirectory)

        // Only set VPC ID if user explicitly provided one via --vpc
        // Otherwise, VPC will be created during 'up'
        val vpc = existingVpcId
        if (vpc != null) {
            eventBus.emit(Event.Setup.ExistingVpc(vpc))
            clusterState.vpcId = vpc
            clusterStateManager.save(clusterState)
        }

        extractResourceFiles()

        displayCompletionMessage(clusterState)

        if (start) {
            eventBus.emit(Event.Setup.ProvisioningInstances)
            // Schedule Up to run after Init's full lifecycle completes
            commandExecutor.schedule { Up() }
        } else {
            eventBus.emit(Event.Setup.InitNextSteps)
        }
    }

    private fun validateParameters() {
        require(resolvedDbCount > 0) { "Number of database instances must be positive" }
        require(resolvedAppCount >= 0) { "Number of application instances cannot be negative" }
        require(ebsSize > 0) { "EBS size must be positive" }
        require(ebsIops >= 0) { "EBS IOPS cannot be negative" }
        require(ebsThroughput >= 0) { "EBS throughput cannot be negative" }
        cidr?.let { CidrBlock(it) }
    }

    private fun checkExistingFiles() {
        val existingFiles = mutableListOf<String>()

        Clean.filesToClean.forEach { file ->
            if (File(context.workingDirectory, file).exists()) {
                existingFiles.add(file)
            }
        }

        Clean.directoriesToClean.forEach { dir ->
            if (File(context.workingDirectory, dir).exists()) {
                existingFiles.add("$dir/")
            }
        }

        if (existingFiles.isNotEmpty()) {
            eventBus.emit(Event.Setup.InitError(existingFiles))
            exitProcess(1)
        }
    }

    private fun prepareEnvironment(): ClusterState {
        if (clean) {
            eventBus.emit(Event.Setup.CleaningExistingConfig)
            // Execute Clean immediately with full lifecycle
            commandExecutor.execute { Clean() }
        }

        val state =
            ClusterState(
                name = name,
                versions = mutableMapOf(),
                initConfig = InitConfig.fromInit(this, userConfig.region),
                tailscaleActive = userConfig.isTailscaleEnabled() && !noTailscale,
            )
        clusterStateManager.save(state)
        return state
    }

    private fun extractResourceFiles() {
        eventBus.emit(Event.Setup.WritingSetupScript)
        extractResourceFile("setup_instance.sh", "setup_instance.sh")
    }

    private fun extractResourceFile(
        resourceName: String,
        targetFileName: String,
    ) {
        this::class.java.getResourceAsStream(resourceName).use { stream ->
            requireNotNull(stream) { "Resource $resourceName not found" }
            File(targetFileName).outputStream().use { output -> stream.copyTo(output) }
        }
    }

    private fun displayCompletionMessage(clusterState: ClusterState) {
        val initConfig = clusterState.initConfig ?: return
        eventBus.emit(
            Event.Setup.WorkspaceInitialized(
                cassandraInstances = initConfig.cassandraInstances,
                instanceType = initConfig.instanceType,
                stressInstances = initConfig.stressInstances,
                region = initConfig.region,
            ),
        )
    }
}
