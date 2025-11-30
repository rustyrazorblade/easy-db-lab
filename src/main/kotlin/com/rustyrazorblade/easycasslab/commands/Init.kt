package com.rustyrazorblade.easycasslab.commands

import com.fasterxml.jackson.annotation.JsonIgnore
import com.github.ajalt.mordant.TermColors
import com.rustyrazorblade.easycasslab.Containers
import com.rustyrazorblade.easycasslab.Context
import com.rustyrazorblade.easycasslab.Docker
import com.rustyrazorblade.easycasslab.annotations.McpCommand
import com.rustyrazorblade.easycasslab.annotations.RequireDocker
import com.rustyrazorblade.easycasslab.annotations.RequireProfileSetup
import com.rustyrazorblade.easycasslab.commands.converters.PicoAZConverter
import com.rustyrazorblade.easycasslab.commands.mixins.SparkInitMixin
import com.rustyrazorblade.easycasslab.configuration.Arch
import com.rustyrazorblade.easycasslab.configuration.ClusterState
import com.rustyrazorblade.easycasslab.configuration.ClusterStateManager
import com.rustyrazorblade.easycasslab.configuration.InitConfig
import com.rustyrazorblade.easycasslab.configuration.User
import com.rustyrazorblade.easycasslab.containers.Terraform
import com.rustyrazorblade.easycasslab.providers.aws.AWS
import com.rustyrazorblade.easycasslab.providers.aws.VpcService
import com.rustyrazorblade.easycasslab.providers.aws.terraform.AWSConfiguration
import com.rustyrazorblade.easycasslab.providers.aws.terraform.EBSConfiguration
import com.rustyrazorblade.easycasslab.providers.aws.terraform.EBSType
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File
import java.time.LocalDate

/**
 * Initialize this directory for easy-cass-lab.
 */
@McpCommand
@RequireDocker
@RequireProfileSetup
@Command(
    name = "init",
    description = ["Initialize this directory for easy-cass-lab"],
)
class Init(
    context: Context,
) : PicoBaseCommand(context) {
    private val userConfig: User by inject()
    private val aws: AWS by inject()
    private val clusterStateManager: ClusterStateManager by inject()
    private val vpcService: VpcService by inject()

    companion object {
        private const val DEFAULT_CASSANDRA_INSTANCE_COUNT = 3
        private const val DEFAULT_EBS_SIZE_GB = 256

        @JsonIgnore val log = KotlinLogging.logger {}

        fun expand(
            region: String,
            azs: List<String>,
        ): List<String> = azs.map { region + it }
    }

    @Option(
        names = ["--cassandra", "-c"],
        description = ["Number of Cassandra instances"],
    )
    var cassandraInstances = DEFAULT_CASSANDRA_INSTANCE_COUNT

    @Option(
        names = ["--stress", "-s"],
        description = ["Number of stress instances"],
    )
    var stressInstances = 0

    @Option(
        names = ["--up"],
        description = ["Start instances automatically"],
    )
    var start = false

    @Option(
        names = ["--instance", "-i"],
        description = ["Instance Type. Set EASY_CASS_LAB_INSTANCE_TYPE to set a default."],
    )
    var instanceType: String = System.getenv("EASY_CASS_LAB_INSTANCE_TYPE") ?: "r3.2xlarge"

    @Option(
        names = ["--stress-instance", "-si", "--si"],
        description = ["Stress Instance Type. Set EASY_CASS_LAB_STRESS_INSTANCE_TYPE to set a default."],
    )
    var stressInstanceType: String = System.getenv("EASY_CASS_LAB_STRESS_INSTANCE_TYPE") ?: "c7i.2xlarge"

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
        description = ["AMI. Set EASY_CASS_LAB_AMI to override the default."],
    )
    var ami: String = System.getenv("EASY_CASS_LAB_AMI") ?: ""

    @Option(
        names = ["--open"],
        description = ["Unrestricted SSH access"],
    )
    var open = false

    @Option(
        names = ["--ebs.type"],
        description = ["EBS Volume Type"],
    )
    var ebsType = EBSType.NONE

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
    )
    var arch: Arch = Arch.AMD64

    @Mixin
    var spark = SparkInitMixin()

    @Option(
        names = ["--tag."],
        description = ["Tag instances"],
    )
    var tags: Map<String, String> = mutableMapOf()

    @Option(
        names = ["--clean"],
        description = ["Clean existing configuration before initializing"],
    )
    var clean = false

    @Option(
        names = ["--vpc"],
        description = ["Use an existing VPC ID instead of creating a new one"],
    )
    var existingVpcId: String? = null

    override fun execute() {
        validateParameters()

        if (!clean) {
            checkExistingFiles()
        }

        val clusterState = prepareEnvironment()

        outputHandler.handleMessage("Initializing directory")

        // Create or use existing VPC
        val vpcId = createOrUseVpc(clusterState)
        clusterState.vpcId = vpcId
        clusterStateManager.save(clusterState)

        val config = buildAWSConfiguration(clusterState, vpcId)
        configureAWSSettings(config)

        initializeTerraform(config)
        extractResourceFiles()

        displayCompletionMessage(config)

        if (start) {
            outputHandler.handleMessage("Provisioning instances")
            Up(context).execute()
        } else {
            with(TermColors()) {
                outputHandler.handleMessage(
                    "Next you'll want to run " + green("easy-cass-lab up") + " to start your instances.",
                )
            }
        }
    }

    /**
     * Creates a new VPC or uses an existing one if --vpc was provided.
     */
    private fun createOrUseVpc(clusterState: ClusterState): String {
        if (existingVpcId != null) {
            outputHandler.handleMessage("Using existing VPC: $existingVpcId")
            return existingVpcId!!
        }

        outputHandler.handleMessage("Creating VPC for cluster: ${clusterState.name}")
        val vpcTags = mapOf("easy_cass_lab" to "1", "ClusterId" to clusterState.clusterId)
        val vpcId = vpcService.findOrCreateVpc(name, "10.0.0.0/16", vpcTags)
        outputHandler.handleMessage("VPC created: $vpcId")
        return vpcId
    }

    private fun validateParameters() {
        require(cassandraInstances > 0) { "Number of Cassandra instances must be positive" }
        require(stressInstances >= 0) { "Number of stress instances cannot be negative" }
        require(ebsSize > 0) { "EBS size must be positive" }
        require(ebsIops >= 0) { "EBS IOPS cannot be negative" }
        require(ebsThroughput >= 0) { "EBS throughput cannot be negative" }
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
            val message =
                buildString {
                    appendLine("Error: Directory already contains configuration files:")
                    existingFiles.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine(
                        "Please use --clean flag to remove existing configuration, " +
                            "or run 'easy-cass-lab clean' first.",
                    )
                }
            outputHandler.handleMessage(message)
            System.exit(1)
        }
    }

    private fun prepareEnvironment(): ClusterState {
        val docker: Docker by inject { parametersOf(context) }
        docker.pullImage(Containers.TERRAFORM)

        if (clean) {
            outputHandler.handleMessage("Cleaning existing configuration...")
            Clean(context).execute()
        }

        val initConfig =
            InitConfig(
                cassandraInstances = cassandraInstances,
                stressInstances = stressInstances,
                instanceType = instanceType,
                stressInstanceType = stressInstanceType,
                azs = azs,
                ami = ami,
                region = userConfig.region,
                name = name,
                ebsType = ebsType.toString(),
                ebsSize = ebsSize,
                ebsIops = ebsIops,
                ebsThroughput = ebsThroughput,
                ebsOptimized = ebsOptimized,
                open = open,
                controlInstances = 1,
                controlInstanceType = "t3.xlarge",
                tags = tags,
            )

        val state =
            ClusterState(
                name = name,
                versions = mutableMapOf(),
                initConfig = initConfig,
            )
        clusterStateManager.save(state)
        return state
    }

    @Suppress("UnusedParameter")
    private fun buildAWSConfiguration(
        clusterState: ClusterState,
        vpcId: String,
    ): AWSConfiguration {
        val ebs = EBSConfiguration(ebsType, ebsSize, ebsIops, ebsThroughput, ebsOptimized)
        return AWSConfiguration(
            name,
            region = userConfig.region,
            context = context,
            user = userConfig,
            ami = ami,
            open = open,
            ebs = ebs,
            numCassandraInstances = cassandraInstances,
            cassandraInstanceType = instanceType,
            numStressInstances = stressInstances,
            stressInstanceType = stressInstanceType,
            arch = arch,
            sparkParams = spark,
            accountId = aws.getAccountId(),
            clusterId = clusterState.clusterId,
        )
    }

    private fun configureAWSSettings(config: AWSConfiguration) {
        outputHandler.handleMessage("Directory Initialized Configuring Terraform")

        for ((key, value) in tags) {
            config.setTag(key, value)
        }

        config.setVariable("NeededUntil", until)

        if (azs.isNotEmpty()) {
            outputHandler.handleMessage("Overriding default az list with $azs")
            config.azs = expand(userConfig.region, azs)
        }
    }

    private fun initializeTerraform(config: AWSConfiguration) {
        outputHandler.handleMessage("Writing OpenTofu Config")
        writeTerraformConfig(config)
    }

    private fun extractResourceFiles() {
        outputHandler.handleMessage("Writing setup_instance.sh")
        extractResourceFile("setup_instance.sh", "setup_instance.sh")
        extractResourceFile("axonops-dashboards.json", "axonops-dashboards.json")

        outputHandler.handleMessage(
            "Creating control directory and writing docker-compose.yaml, " +
                "otel-collector-config.yaml.",
        )
        File("control").mkdirs()
        extractResourceFile("docker-compose-control.yaml", "control/docker-compose.yaml")
        extractResourceFile("otel-collector-config.yaml", "control/otel-collector-config.yaml")

        outputHandler.handleMessage(
            "Creating cassandra directory and writing OTel configs for Cassandra nodes",
        )
        File("cassandra").mkdirs()
        extractResourceFile("otel-cassandra-config.yaml", "cassandra/otel-cassandra-config.yaml")
        extractResourceFile("docker-compose-cassandra.yaml", "cassandra/docker-compose.yaml")
        extractResourceFile("cassandra-sidecar.yaml", "cassandra/cassandra-sidecar.yaml")

        outputHandler.handleMessage(
            "Creating stress directory and writing OTel configs for stress nodes",
        )
        File("stress").mkdirs()
        extractResourceFile("otel-stress-config.yaml", "stress/otel-stress-config.yaml")
        extractResourceFile("docker-compose-stress.yaml", "stress/docker-compose.yaml")
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

    private fun displayCompletionMessage(config: AWSConfiguration) {
        outputHandler.handleMessage(
            "Your workspace has been initialized with $cassandraInstances Cassandra instances " +
                "(${config.cassandraInstanceType}) and $stressInstances stress instances " +
                "in ${userConfig.region}",
        )
    }

    private fun writeTerraformConfig(config: AWSConfiguration): Result<String> {
        val configOutput = File("terraform.tf.json")
        config.write(configOutput)

        val terraform = Terraform(context)
        outputHandler.handleMessage("Calling init")
        return terraform.init()
    }
}
