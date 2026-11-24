package com.rustyrazorblade.easydblab.commands

import com.fasterxml.jackson.annotation.JsonIgnore
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.converters.NodeCapabilityNames
import com.rustyrazorblade.easydblab.commands.converters.PicoAZConverter
import com.rustyrazorblade.easydblab.commands.converters.PicoCniModeConverter
import com.rustyrazorblade.easydblab.commands.converters.PicoPositionalClusterNameConverter
import com.rustyrazorblade.easydblab.commands.mixins.OpenSearchInitMixin
import com.rustyrazorblade.easydblab.commands.mixins.SparkInitMixin
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.CapabilityOverrides
import com.rustyrazorblade.easydblab.configuration.ClientNetworks
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.Exposure
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.NetworkMode
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.NodeOrigin
import com.rustyrazorblade.easydblab.configuration.RoleSelectors
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.kernel.DeclaresEnvironment
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.WorkspaceKubeconfig
import com.rustyrazorblade.easydblab.network.CidrBlock
import com.rustyrazorblade.easydblab.providers.docker.DockerClientProvider
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import com.rustyrazorblade.easydblab.services.AbsentCapabilityReport
import com.rustyrazorblade.easydblab.services.ClientNetworkProbe
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.LiveInfrastructureLookup
import com.rustyrazorblade.easydblab.services.ObservabilityStage
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import com.rustyrazorblade.easydblab.services.aws.DataDiskRequirement
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.InstanceTypeCapabilities
import io.fabric8.kubernetes.api.model.Quantity
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.get
import org.koin.core.component.inject
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.Spec
import java.nio.file.Path
import java.time.LocalDate
import kotlin.system.exitProcess

/**
 * Initialize this directory for easy-db-lab.
 *
 * IMPORTANT: This command only sets up local configuration files and state.
 * It does NOT provision any infrastructure (VPC, EC2 instances, containers, etc.).
 *
 * Responsibilities of init:
 * - Record the execution environment in state.json, with the cluster configuration: EC2 plus K3s
 *   by default, K3s in Docker on this machine with `--local` (and `--data-dir`)
 * - Extract resource files (manifests, scripts, etc.)
 * - Record the network mode in the workspace env file (direct for `--local`)
 * - Store user-provided tags for later use during provisioning
 *
 * It declares the environment it records ([DeclaresEnvironment]), so the executor asks for an AWS
 * profile only for an environment that has an AWS account: `init --local` never loads the profile
 * or builds an AWS client. `--local` refuses every AWS-only option given on the command line, and
 * `--up`, since only EC2 plus K3s has a provisioner. Everything that can fail (option checks,
 * capability overrides, the cluster state, including the Docker daemon's architecture) is resolved before
 * `--clean` removes anything.
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
@RequireProfileSetup
@Command(
    name = "init",
    description = ["Initialize this directory for easy-db-lab"],
)
class Init :
    PicoBaseCommand(),
    DeclaresEnvironment {
    private val userConfig: User by inject()
    private val commandExecutor: CommandExecutor by inject()
    private val ec2InstanceService: EC2InstanceService by inject()
    private val liveInfrastructure by lazy { LiveInfrastructureLookup(clusterStateManager) { get<AwsInfrastructureService>() } }

    // Only init --local resolves it: the nodes are containers, so they run the Docker daemon's architecture.
    private val dockerClientProvider: DockerClientProvider by inject()
    private val clientNetworkProbe: ClientNetworkProbe by inject()

    companion object {
        private const val DEFAULT_CASSANDRA_INSTANCE_COUNT = 3
        private const val DEFAULT_EBS_SIZE_GB = 256

        /** Control node instance type. Kept in sync with [InitConfig] control defaults. */
        const val DEFAULT_CONTROL_INSTANCE_TYPE = Constants.InstanceTypes.CONTROL

        /** Classpath directory holding the OTel agent's Cassandra JMX rule files. */
        private const val CASSANDRA_RESOURCE_DIR = "/com/rustyrazorblade/easydblab/configuration/cassandra"

        /** Creates a K3s in Docker workspace on this machine. */
        const val LOCAL_OPTION = "--local"

        /** Runs `up` after `init`; refused with [LOCAL_OPTION], which has no provisioner yet. */
        const val UP_OPTION = "--up"

        /** Where a `--local` workspace keeps its observability data. */
        const val DATA_DIR_OPTION = "--data-dir"

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
        names = [UP_OPTION],
        description = ["Start instances automatically"],
    )
    var start = false

    @Option(
        names = ["--quiet", "-q"],
        description = ["With --up: hide the output of each node's scripts; Ready lines, progress and failures still print"],
    )
    var quiet = false

    @Option(
        names = ["--instance", "-i"],
        description = [
            "Legacy alias for --db.instance-type. Database instance type. " +
                "Set EASY_DB_LAB_INSTANCE_TYPE to set a default.",
        ],
    )
    var instanceType: String = System.getenv("EASY_DB_LAB_INSTANCE_TYPE") ?: Constants.InstanceTypes.DB

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
    var stressInstanceType: String = System.getenv("EASY_DB_LAB_STRESS_INSTANCE_TYPE") ?: Constants.InstanceTypes.APP

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
        description = ["Limit to specified availability zones (at most 8)"],
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

    /** Never holds a value: its converter throws, so `--name` fails at parse time naming the positional form. */
    @Option(
        names = ["--name"],
        hidden = true,
        converter = [PicoPositionalClusterNameConverter::class],
        description = ["Not an option. The cluster name is positional: init <name>"],
    )
    var misplacedName: String? = null

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
        names = ["--node-label"],
        description = [
            "Node label the observability backends, Grafana and kube-state-metrics are scheduled by " +
                "(format: key=value, can be repeated), added to the environment's own placement. " +
                "The label must already be on a node; the deploy fails if no node matches.",
        ],
        arity = "1..*",
        split = ",",
    )
    var nodeLabels: Map<String, String> = mutableMapOf()

    @Option(
        names = [RoleSelectors.OPTION],
        description = [
            "Nodes one role's pods run on (format: <role>=<key=value>[,<key=value>...], role db, app or " +
                "control; can be repeated, once per role). Replaces that role's default node selector; " +
                "a role not given keeps the environment's.",
        ],
    )
    var roleNodes: List<String> = mutableListOf()

    @Option(
        names = [ClientNetworks.OPTION],
        description = [
            "A network the database kits accept passwordless clients from, as an IPv4 CIDR or address " +
                "(can be repeated). Added to the VPC CIDR and the K3s cluster CIDR.",
        ],
    )
    var clientNetworks: List<String> = mutableListOf()

    @Option(
        names = ["--kit-storage-class"],
        description = [
            "StorageClass every kit claim names. Omit on an AWS cluster to keep kits on per-kit local " +
                "volumes on the nodes' NVMe. The class must reclaim with Retain.",
        ],
    )
    var kitStorageClass: String? = null

    @Option(
        names = ["--kit-storage-size"],
        description = [
            "Default size of a kit claim, as a Kubernetes quantity (e.g. 200Gi). Default: " +
                "${Constants.K8s.LOCAL_KIT_STORAGE_SIZE} on local volumes, " +
                "${Constants.K8s.DEFAULT_PROVISIONED_KIT_STORAGE_SIZE} on a --kit-storage-class. " +
                "A kit's own --storage-size wins.",
        ],
    )
    var kitStorageSize: String? = null

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
        description = [
            "VPC CIDR block (e.g. 10.2.0.0/16). Must be /25 or larger; it is split into eight equal subnets. " +
                "Omit to auto-select a non-conflicting block.",
        ],
    )
    var cidr: String? = null

    @Option(
        names = ["--cni"],
        description = [
            "Pod-network CNI: 'cilium' (default) uses Cilium ENI native routing (no encapsulation); " +
                "'flannel' uses K3s's built-in Flannel overlay.",
        ],
        converter = [PicoCniModeConverter::class],
    )
    var cni: CniMode = CniMode.Cilium

    @Option(
        names = ["--tenant"],
        description = [
            "Observability tenant the cluster's traces, profiles, metrics, logs and annotations " +
                "belong to (default: default). Must match ^[a-z][a-z0-9_-]{0,62}$.",
        ],
    )
    var tenant: String = Constants.Observability.DEFAULT_TENANT

    @Option(
        names = ["--namespace"],
        description = [
            "Kubernetes namespace easy-db-lab installs the observability stack into (default: default, " +
                "in every environment, including your own cluster). Must be a DNS-1123 label of at most 63 characters, " +
                "not kube-system, kube-public or kube-node-lease.",
        ],
    )
    var namespace: String? = null

    @Option(
        names = [Constants.Capabilities.OVERRIDE_OPTION],
        description = [
            "Override one node capability, as <name>=true or <name>=false (repeatable). " +
                "Names: \${COMPLETION-CANDIDATES}.",
        ],
        completionCandidates = NodeCapabilityNames::class,
    )
    var capabilityOverrides: List<String> = emptyList()

    @Option(
        names = ["--redirect-telemetry"],
        description = [
            "Ship all telemetry to another easy-db-lab cluster instead of standing up local " +
                "backends. HOST is a bare host or IP of a node in that cluster (no scheme, no port): " +
                "the four signal endpoints are derived from the NodePorts it publishes its backends on. " +
                "For a stack that is not an easy-db-lab cluster, set all four --redirect-*-endpoint options.",
        ],
    )
    var redirectTelemetry: String? = null

    @Option(
        names = ["--redirect-metrics-endpoint"],
        description = ["Override the derived metrics remote-write URL for --redirect-telemetry."],
    )
    var redirectMetricsEndpoint: String? = null

    @Option(
        names = ["--redirect-logs-endpoint"],
        description = ["Override the derived logs OTLP ingest URL for --redirect-telemetry."],
    )
    var redirectLogsEndpoint: String? = null

    @Option(
        names = ["--redirect-traces-endpoint"],
        description = ["Override the derived Tempo OTLP endpoint (host:port) for --redirect-telemetry."],
    )
    var redirectTracesEndpoint: String? = null

    @Option(
        names = ["--redirect-profiles-endpoint"],
        description = ["Override the derived Pyroscope ingest base URL for --redirect-telemetry."],
    )
    var redirectProfilesEndpoint: String? = null

    @Option(
        names = [LOCAL_OPTION],
        description = [
            "Create a K3s in Docker workspace on this machine. Needs no AWS account or profile. " +
                "Options that configure AWS instances, networking or services are refused.",
        ],
    )
    var local = false

    @Option(
        names = [DATA_DIR_OPTION],
        description = [
            "With --local: the directory the observability data is kept in. A relative path is resolved " +
                "against the current directory of the easy-db-lab process, like every other path option, " +
                "and recorded absolute (default: ${Constants.Local.DEFAULT_DATA_DIRECTORY} in the workspace). " +
                "'clean' never removes it.",
        ],
    )
    var dataDir: String? = null

    /** Picocli's model of this command, for the usage error and for which options were given. */
    @Spec
    var spec: CommandSpec? = null

    /**
     * The telemetry redirect target built from the redirect options, or null in local mode
     * (`--redirect-telemetry` not supplied). The four endpoints derive from the base host and the
     * known stack ports; per-signal overrides replace individual derived values.
     */
    @get:JsonIgnore
    val resolvedTelemetryRedirect: TelemetryRedirect?
        get() =
            redirectTelemetry?.let { host ->
                TelemetryRedirect.fromBaseHost(
                    baseHost = host,
                    metricsOverride = redirectMetricsEndpoint,
                    logsOverride = redirectLogsEndpoint,
                    tracesOverride = redirectTracesEndpoint,
                    profilesOverride = redirectProfilesEndpoint,
                )
            }

    /**
     * Why `--redirect-telemetry` cannot work as given, or null when it can (or is not supplied).
     *
     * The base host is a node of a receiving easy-db-lab cluster, and every derived endpoint uses
     * that cluster's NodePorts, so a value carrying a scheme or a port (an external stack on its own
     * ports) derives endpoints nothing listens on. That is allowed only when every signal is
     * overridden, since then nothing is derived from the host.
     */
    internal fun redirectTelemetryProblem(): String? {
        val host = redirectTelemetry ?: return null
        val overrides = listOf(redirectMetricsEndpoint, redirectLogsEndpoint, redirectTracesEndpoint, redirectProfilesEndpoint)
        val derivesSomething = overrides.any { it == null }
        return when {
            derivesSomething && (host.isBlank() || ':' in host || '/' in host) ->
                "--redirect-telemetry '$host' must be a bare host or IP of a node in an easy-db-lab cluster: " +
                    "the endpoints are derived from that cluster's NodePorts (Mimir ${Constants.K8s.MIMIR_NODE_PORT}, " +
                    "Loki ${Constants.K8s.LOKI_NODE_PORT}, Tempo OTLP ${Constants.K8s.TEMPO_OTLP_GRPC_NODE_PORT}, " +
                    "Pyroscope ${Constants.K8s.PYROSCOPE_NODE_PORT}). For any other stack, set --redirect-metrics-endpoint, " +
                    "--redirect-logs-endpoint, --redirect-traces-endpoint and --redirect-profiles-endpoint."
            else -> null
        }
    }

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

    /** The environment this `init` records, which decides whether it needs an AWS profile. */
    override fun declaredEnvironment(): Environment =
        when (local) {
            true -> Environment.K3sDocker(resolvedDataDir())
            false -> Environment.DEFAULT
        }

    /**
     * The data directory as `state.json` records it: absolute and normalized. A given [dataDir] is a
     * path the user typed, so like every other path option (`cql --file`, `grafana install`) a relative
     * one is resolved against the process's current directory, which in the REPL or the server need
     * not be the workspace. With none, it is [Constants.Local.DEFAULT_DATA_DIRECTORY] in the workspace.
     */
    private fun resolvedDataDir(): String =
        when (val given = dataDir) {
            null -> context.workingDirectory.toPath().resolve(Constants.Local.DEFAULT_DATA_DIRECTORY)
            else -> Path.of(given)
        }.toAbsolutePath()
            .normalize()
            .toString()

    /**
     * The API of a cluster the user brings, or null when `init` builds the nodes (AWS). The
     * `init --kubeconfig` option (#1082) sets it; until then only tests do.
     */
    @get:JsonIgnore
    internal var broughtCluster: KubeApi? = null

    /**
     * Where this workspace's nodes come from, which decides the role selectors `--role-nodes`
     * applies to: [NodeOrigin.OWNED] for the AWS nodes `init` builds and labels, and
     * [NodeOrigin.BROUGHT] for a [broughtCluster], where every role runs on any node until it is mapped.
     */
    private val nodeOrigin: NodeOrigin
        get() = if (broughtCluster == null) NodeOrigin.OWNED else NodeOrigin.BROUGHT

    override fun execute() {
        refuseOptionsThatNeedOtherOptions()
        validateParameters()
        // Parsed before anything is cleaned or written, so a bad override or mapping leaves the directory as it was.
        val capabilities = effectiveCapabilities()
        val roleSelectors = RoleSelectors.base(nodeOrigin).withMappings(RoleSelectors.parse(roleNodes))
        // Probed before anything is cleaned or written too: a cluster that reports no network leaves the directory as it was.
        val clients = InitClientNetworks.resolve(clientNetworks, broughtCluster) { clientNetworkProbe }

        if (!clean) checkExistingFiles()
        // Built before --clean runs, so a failure here (an unknown instance type, an unsupported Docker
        // architecture) leaves the directory as it was.
        val clusterState = clients.applyTo(buildClusterState(capabilities).copy(roleSelectors = roleSelectors))
        if (clean) {
            refuseLiveInfrastructure()
            eventBus.emit(Event.Setup.CleaningExistingConfig)
            // Execute Clean immediately with full lifecycle; a failed clean fails init.
            runNested(commandExecutor, Clean())
        }
        // After the cleanup, which removes a bin/ that holds only easy-db-lab's wrappers.
        refuseExistingBin()

        // The shell-side tools learn a direct network mode from the env file. It is written before state.json, so
        // a failure between the two never leaves a recorded environment whose env file says otherwise. This is
        // the one mode write that does not go through WorkspaceShellTools: the workspace has no bin/ (refused
        // above), so no wrapper from an earlier release can read the file, and the wrappers come with `up`.
        val contract = ClusterContract.of(clusterState)
        val networkMode = contract.networkMode
        if (networkMode == NetworkMode.Direct) ProxyEnvFile(context.workingDirectory).recordNetworkMode(networkMode)
        clusterStateManager.replace(clusterState)

        eventBus.emit(Event.Setup.InitializingDirectory)
        clients.report(clusterState, eventBus)

        existingVpcId?.let { eventBus.emit(Event.Setup.ExistingVpc(it)) }

        extractResourceFiles()

        displayCompletionMessage(clusterState, contract)
        reportAbsentCapabilities(capabilities, clusterState)

        if (start) {
            eventBus.emit(Event.Setup.ProvisioningInstances)
            // Schedule Up to run after Init's full lifecycle completes
            commandExecutor.schedule { Up().also { it.quiet = quiet } }
        } else {
            eventBus.emit(nextSteps(contract))
        }
    }

    /** `up` only where it can provision; elsewhere the user brings the cluster up and installs kits. */
    private fun nextSteps(contract: ClusterContract): Event.Setup =
        when (contract.hasProvisioner) {
            true -> Event.Setup.InitNextSteps
            false -> Event.Setup.LocalInitNextSteps(kubeconfig = WorkspaceKubeconfig.file(context.workingDirectory).path)
        }

    /**
     * Fails before anything is cleaned or written when an option is given without the one it needs:
     * `--data-dir` without `--local` and `--local --up` ([Event.Setup.InitOptionConflict]), and every
     * AWS-only option given explicitly with `--local`, naming each ([Event.Setup.LocalOptionsRefused]).
     */
    private fun refuseOptionsThatNeedOtherOptions() {
        if (!local) {
            if (dataDir != null) refuseConflict(DATA_DIR_OPTION, "requires $LOCAL_OPTION")
            return
        }
        // No local provisioner exists yet (#1041): `up` would refuse the workspace this just wrote.
        if (start) refuseConflict(UP_OPTION, "cannot be used with $LOCAL_OPTION: easy-db-lab cannot provision K3s in Docker yet")
        val refused = AwsOnlyOptions.givenIn(parseResult())
        if (refused.isEmpty()) return
        eventBus.emit(Event.Setup.LocalOptionsRefused(refused))
        throw CommandFailedException("${refused.joinToString(", ")} cannot be used with $LOCAL_OPTION")
    }

    private fun refuseConflict(
        option: String,
        problem: String,
    ): Nothing {
        eventBus.emit(Event.Setup.InitOptionConflict(option, problem))
        throw CommandFailedException("$option $problem")
    }

    /**
     * What was typed, which the AWS-only refusal reads. A command built in code has none, and a new
     * command line would report nothing given, so the refusal would pass every option: that fails here.
     */
    private fun parseResult(): CommandLine.ParseResult =
        checkNotNull(spec?.commandLine()?.parseResult) {
            "init $LOCAL_OPTION must be parsed by picocli, which records the options given; this one was built in code"
        }

    private fun requireKitStorageSize(value: String) {
        val bytes = runCatching { Quantity.getAmountInBytes(Quantity.parse(value)) }.getOrNull()
        require(bytes != null && bytes.signum() > 0) {
            "Invalid --kit-storage-size '$value': give a positive Kubernetes quantity such as 200Gi or 1Ti."
        }
    }

    private fun validateParameters() {
        require(!quiet || start) { "--quiet hides the output of the nodes 'up' brings up, so it needs --up" }
        require(resolvedDbCount > 0) { "Number of database instances must be positive" }
        require(resolvedAppCount >= 0) { "Number of application instances cannot be negative" }
        require(ebsSize > 0) { "EBS size must be positive" }
        require(ebsIops >= 0) { "EBS IOPS cannot be negative" }
        require(ebsThroughput >= 0) { "EBS throughput cannot be negative" }
        cidr?.let { CidrBlock(it) }?.let { block ->
            require(!block.overlapsK3sRanges()) {
                "CIDR $block overlaps K3s's pod range ${Constants.K3s.CLUSTER_CIDR} or service range " +
                    "${Constants.K3s.SERVICE_CIDR}; node and pod addresses would collide. Pick another block."
            }
        }
        require(azs.size <= Constants.Vpc.MAX_AVAILABILITY_ZONES) {
            "A cluster can use at most ${Constants.Vpc.MAX_AVAILABILITY_ZONES} availability zones, " +
                "because the VPC is split into ${Constants.Vpc.MAX_AVAILABILITY_ZONES} subnets; got ${azs.size}: " +
                azs.joinToString(",")
        }
        kitStorageSize?.let { requireKitStorageSize(it) }
        require(Regex(Constants.Observability.CLUSTER_NAME_PATTERN).matches(name)) {
            "Invalid cluster name '$name': a cluster name must match ${Constants.Observability.CLUSTER_NAME_PATTERN} " +
                "(start with a lowercase letter; then lowercase letters, digits or '-'; at most 40 characters)."
        }
        require(Regex(Constants.Observability.TENANT_PATTERN).matches(tenant)) {
            "Invalid tenant '$tenant': a tenant must match ${Constants.Observability.TENANT_PATTERN} " +
                "(start with a lowercase letter; then lowercase letters, digits, '_' or '-'; at most 63 characters)."
        }
        require(tenant != Constants.Observability.RESERVED_TENANT) {
            "Invalid tenant '$tenant': '${Constants.Observability.RESERVED_TENANT}' is reserved " +
                "(a Loki tenant with that name would collide with Loki's index path)."
        }
        namespace?.let { K8sNamespace(it) }
        redirectTelemetryProblem()?.let { problem -> throw IllegalArgumentException(problem) }
        resolvedTelemetryRedirect?.validate()?.let { offending ->
            require(offending.isEmpty()) {
                "Telemetry redirect endpoints are missing or malformed for: " +
                    "${offending.joinToString(", ")}. Fix the base host or the matching " +
                    "--redirect-*-endpoint override."
            }
        }
    }

    /**
     * The capability set `init` records: the declared set of the environment it records, with the
     * operator's `--capability` overrides applied.
     *
     * @throws IllegalArgumentException on an unknown capability, a bad value or conflicting overrides
     */
    private fun effectiveCapabilities(): NodeCapabilities =
        CapabilityOverrides.parse(capabilityOverrides).applyTo(declaredEnvironment().declaredCapabilities)

    /** Prints each absent capability and the stages `up` will skip without it. */
    private fun reportAbsentCapabilities(
        capabilities: NodeCapabilities,
        clusterState: ClusterState,
    ) {
        val stages = ObservabilityStage.declared(clusterState.initConfig?.telemetryRedirect).map { it.requirement }
        AbsentCapabilityReport.render(capabilities, stages)?.let { println(it) }
    }

    private fun checkExistingFiles() {
        val existingFiles = mutableListOf<String>()

        Clean.filesToClean.forEach { file ->
            if (context.workspaceFile(file).exists()) {
                existingFiles.add(file)
            }
        }

        Clean.directoriesToClean.forEach { dir ->
            if (context.workspaceFile(dir).exists()) {
                existingFiles.add("$dir/")
            }
        }

        if (existingFiles.isNotEmpty()) {
            eventBus.emit(Event.Setup.InitError(existingFiles))
            exitProcess(1)
        }
    }

    /**
     * `--clean` deletes `state.json`, the only local record of the cluster's VPC. Ask AWS first,
     * and refuse while the cluster still has resources: they would keep running with no workspace
     * pointing at them. Nothing has been deleted when this throws.
     */
    private fun refuseLiveInfrastructure() {
        val live = liveInfrastructure.find() ?: return
        eventBus.emit(
            Event.Setup.CleanRefusedLiveInfrastructure(live.vpcId, live.instanceIds, live.nonInstanceResourceCount()),
        )
        throw CommandFailedException("The cluster in ${context.workingDirectory.absolutePath} still has infrastructure in ${live.vpcId}")
    }

    /**
     * Refuses a directory that already has a `bin/`, before anything is written. The workspace
     * `bin/` holds easy-db-lab's tool wrappers, and this is what keeps a source checkout or a
     * project directory from being used as a workspace.
     *
     * @throws CommandFailedException after reporting [Event.Setup.WorkspaceHasBinDirectory]
     */
    private fun refuseExistingBin() {
        if (!context.workspaceFile(Constants.ToolWrappers.DIRECTORY).exists()) return
        eventBus.emit(Event.Setup.WorkspaceHasBinDirectory(context.workingDirectory.absolutePath))
        throw CommandFailedException("${context.workingDirectory.absolutePath} already has a bin/")
    }

    /**
     * The state this `init` records, not yet saved. An environment with an AWS account takes its
     * region, Tailscale and architectures from the profile and EC2; any other reads neither.
     */
    private fun buildClusterState(capabilities: NodeCapabilities): ClusterState {
        val environment = declaredEnvironment()
        return when (NodeCapability.AWS_ACCOUNT in environment.declaredCapabilities) {
            true -> awsClusterState(capabilities)
            false -> localClusterState(environment, capabilities)
        }
    }

    /** The state of an EC2 + K3s cluster: region and Tailscale from the profile, architectures from EC2. */
    private fun awsClusterState(capabilities: NodeCapabilities): ClusterState {
        val tailscaleActive = userConfig.isTailscaleEnabled() && !noTailscale
        return ClusterState(
            name = name,
            initConfig =
                InitConfig.fromInit(
                    init = this,
                    region = userConfig.region,
                    dbArch = nodeArch(ServerType.Cassandra, resolvedDbInstanceType),
                    appArch = nodeArch(ServerType.Stress, resolvedAppInstanceType),
                    controlArch = nodeArch(ServerType.Control, DEFAULT_CONTROL_INSTANCE_TYPE),
                ),
            networkMode = if (tailscaleActive) NetworkMode.Tailscale else NetworkMode.Socks,
            capabilities = capabilities.present().toSet(),
            exposure = Exposure.atInit(tailscaleActive = tailscaleActive),
        ).apply {
            // Only a VPC the user gave with --vpc is recorded here; otherwise up creates one.
            existingVpcId?.let { vpc ->
                vpcId = vpc
                vpcSupplied = true
            }
        }
    }

    /**
     * The Docker daemon's architecture. A daemon that does not answer is reported as
     * [Event.Setup.LocalDockerUnavailable] and fails `init`; this runs before anything is cleaned or written.
     * An architecture the daemon reports but easy-db-lab does not support fails through [Arch.fromOsArch].
     */
    private fun dockerArchitecture(): Arch {
        val reported =
            runCatching { dockerClientProvider.getDockerClient().serverArchitecture() }.getOrElse { e ->
                if (e is InterruptedException) throw e
                val detail = generateSequence(e) { it.cause }.last().let { "${it::class.simpleName}: ${it.message}" }
                eventBus.emit(Event.Setup.LocalDockerUnavailable(detail))
                throw CommandFailedException("init --local cannot reach the Docker daemon", e)
            }
        return Arch.fromOsArch(reported)
    }

    /**
     * The state of a K3s in Docker workspace. It reads neither the AWS profile nor EC2: the Services
     * are reached by a port-forward on loopback, and the nodes run the Docker daemon's
     * architecture, which is not always this JVM's (an x86_64 JVM under Rosetta talks to an arm64 daemon).
     */
    private fun localClusterState(
        environment: Environment,
        capabilities: NodeCapabilities,
    ): ClusterState =
        ClusterState(
            name = name,
            initConfig = InitConfig.forLocal(init = this, hostArch = dockerArchitecture()),
            networkMode = NetworkMode.Direct,
            capabilities = capabilities.present().toSet(),
            exposure = Exposure.PortForward,
            environment = environment,
        )

    /**
     * Derives the CPU architecture of an instance type from its EC2 `SupportedArchitectures`.
     *
     * Fails fast (before any provisioning) if the instance type is unknown in the region or its
     * architectures do not resolve to a single [Arch] — the architecture is never defaulted.
     */
    private fun deriveArch(capabilities: InstanceTypeCapabilities): Arch = Arch.fromEc2(capabilities.supportedArchitectures)

    /**
     * Reads [instanceType] once, checks that a [serverType] node of it has a data disk (see
     * [DataDiskRequirement]), and returns its architecture. Every node type is checked, whatever
     * the node count, as the instance-storage-validation spec requires.
     */
    private fun nodeArch(
        serverType: ServerType,
        instanceType: String,
    ): Arch {
        val capabilities = ec2InstanceService.describeInstanceType(instanceType)
        DataDiskRequirement.check(serverType, instanceType, capabilities.hasInstanceStore, ebsConfigured = ebsType != "NONE")
        return deriveArch(capabilities)
    }

    private fun extractResourceFiles() {
        eventBus.emit(Event.Setup.WritingSetupScript)
        extractResourceFile(Constants.SETUP_INSTANCE_SCRIPT, Constants.SETUP_INSTANCE_SCRIPT)
        // The OTel agent's JMX rules live in the workspace so an operator can edit them and push
        // them again with `setup-instances`, without a Gradle rebuild.
        extractResourceFile(
            "$CASSANDRA_RESOURCE_DIR/${Constants.Cassandra.JMX_RULES_FILE}",
            Constants.Cassandra.JMX_RULES_FILE,
        )
    }

    private fun extractResourceFile(
        resourceName: String,
        targetFileName: String,
    ) {
        this::class.java.getResourceAsStream(resourceName).use { stream ->
            requireNotNull(stream) { "Resource $resourceName not found" }
            // Into the workspace, which is not always the process working directory (Server, Repl).
            context.workspaceFile(targetFileName).outputStream().use { output -> stream.copyTo(output) }
        }
    }

    private fun displayCompletionMessage(
        clusterState: ClusterState,
        contract: ClusterContract,
    ) {
        val initConfig = clusterState.initConfig ?: return
        contract.localDataDirectory?.let { dataDir ->
            eventBus.emit(
                Event.Setup.LocalWorkspaceInitialized(
                    dataDir = dataDir,
                    dbCount = initConfig.cassandraInstances,
                    appCount = initConfig.stressInstances,
                ),
            )
            return
        }
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
