package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import com.rustyrazorblade.easydblab.proxy.ProxyAvailability
import com.rustyrazorblade.easydblab.server.AccessInfo
import com.rustyrazorblade.easydblab.server.CassandraVersionInfo
import com.rustyrazorblade.easydblab.server.ClusterInfo
import com.rustyrazorblade.easydblab.server.ClusterStatusCollector
import com.rustyrazorblade.easydblab.server.KitAccess
import com.rustyrazorblade.easydblab.server.NetworkingInfo
import com.rustyrazorblade.easydblab.server.NodeInfo
import com.rustyrazorblade.easydblab.server.NodesInfo
import com.rustyrazorblade.easydblab.server.OpenSearchInfo
import com.rustyrazorblade.easydblab.server.S3Info
import com.rustyrazorblade.easydblab.server.SecurityGroupRuleResponse
import com.rustyrazorblade.easydblab.server.SparkInfo
import com.rustyrazorblade.easydblab.server.StatusReport
import com.rustyrazorblade.easydblab.services.WorkspaceKitScanner
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Displays comprehensive, human-readable environment status including:
 * - Nodes (cassandra, stress, control) with instance IDs, IPs, aliases, and live state
 * - Networking info (VPC, IGW, subnets, route tables)
 * - Security group rules (full ingress/egress)
 * - Installed kits, with the NodePort endpoints of each running kit
 * - Kubernetes jobs running on K3s cluster
 * - Cassandra version (live via SSH, fallback to cached)
 *
 * ## The one permitted exception to fail-fast
 *
 * Every other `@RequiresProxy` command aborts when the SOCKS5 tunnel cannot be established.
 * `Status` is the single, deliberate exception: it carries `@RequiresProxy(tolerateFailure =
 * true)`, so a proxy failure is recorded on [ProxyAvailability] instead of aborting the command
 * (see `DefaultCommandExecutor.checkRequirements()`).
 *
 * Unavailability follows the **transport** a section actually uses, not a hand-picked list.
 * Sections sourced from cluster state, the AWS SDK, or direct SSH to the nodes still render when
 * the proxy is down — SSH never traverses the tunnel, which exists only to reach the private
 * Kubernetes API. Only the section sourced from that API (stress jobs) is marked unavailable,
 * via [renderProxyDependentSection]. There is exactly
 * one rendering path ([execute]) for both the healthy and degraded reports, so a section added
 * to the healthy report cannot silently vanish from the degraded one unless it too depends on
 * the Kubernetes API.
 *
 * This is permitted only because `Status` is read-only — it changes no cluster state, so
 * reporting a partial view can never leave the cluster in an unexpected condition. It is why
 * `status` is the command a user reaches for when the cluster is already broken. It still exits
 * non-zero when degraded (see [StatusDegradedException]), so a partial report is never mistaken
 * by a script for a healthy cluster. See design decision D9 in
 * `openspec/changes/up-fail-fast/design.md`. **No command that mutates state may take this
 * exception.**
 */
@RequireProfileSetup
@RequiresProxy(tolerateFailure = true)
@Command(
    name = "status",
    description = ["Display full environment status"],
)
@Suppress("TooManyFunctions")
class Status :
    PicoCommand,
    KoinComponent {
    private val proxyAvailability: ProxyAvailability by inject()

    companion object {
        private const val POD_NAME_MAX_LENGTH = 40
    }

    private val eventBus: EventBus by inject()
    private val clusterStateManager: ClusterStateManager by inject()
    private val workspaceKitScanner: WorkspaceKitScanner by inject()
    private val collector: ClusterStatusCollector by inject()

    private val clusterState by lazy { clusterStateManager.load() }

    override fun execute() {
        if (!clusterStateManager.exists()) {
            eventBus.emit(Event.Status.NoClusterState)
            return
        }

        // One collection, shared with the REST status cache: this command only renders it. The
        // Kubernetes pods are not shown here, so they are not fetched; the stress jobs come from
        // the private Kubernetes API, so they are skipped when the proxy could not be established.
        val report =
            collector.collect(
                clusterState,
                includePods = false,
                kubernetesUnreachable = proxyAvailability.failure()?.let { proxyFailureText(it) },
            )
        val status = report.response

        // One rendering path for both the healthy and degraded reports. Every section renders by
        // default; only the one sourced from the private Kubernetes API is wrapped in
        // renderProxyDependentSection, so a proxy failure marks exactly that one unavailable while
        // every AWS-, cluster-state-, and SSH-backed section still renders. A section added here
        // renders unless it is deliberately wrapped — it can never silently vanish when degraded.
        displayClusterSection(status.cluster)
        displayNodesSection(status.nodes)
        displayNetworkingSection(status.networking)
        displaySecurityGroupSection(report)
        displaySparkClusterSection(status.spark)
        displayOpenSearchSection(status.opensearch)
        displayS3BucketSection(status.s3, clusterState)
        displayKitsSection(report.kits)
        renderProxyDependentSection("Stress jobs") { displayStressJobsSection(report) }
        displayObservabilitySection(status.accessInfo)
        displayS3ManagerSection(status.accessInfo)
        displayCassandraVersionSection(status.cassandraVersion)

        // A degraded report is still an unsuccessful command: fail after rendering so a script
        // can never read a partial report as a healthy cluster. See StatusDegradedException.
        val proxyFailure = proxyAvailability.failure()
        if (proxyFailure != null) {
            throw StatusDegradedException(proxyFailure)
        }
    }

    /**
     * Renders a status section whose data comes from the private Kubernetes API (Fabric8 via
     * `K8sClientProvider`) and therefore depends on the SOCKS tunnel.
     *
     * When the proxy failed to establish (recorded on [ProxyAvailability]; see the class KDoc and
     * design decision D9), the section is reported as [Event.Status.SectionUnavailable] with the
     * proxy failure as its reason, instead of being read. Otherwise it renders normally via
     * [render].
     *
     * This is the ONLY place a section is skipped on a proxy failure, and it is keyed on the
     * transport — not on a hand-maintained list of section names. Sections sourced from cluster
     * state, the AWS SDK, or direct SSH to the nodes are never wrapped here, because SSH never
     * traverses the tunnel and neither the AWS APIs nor cluster state touch it; they always
     * render, degraded or not. There is deliberately no second "degraded" rendering routine that
     * could fall out of sync with this one.
     */
    private fun renderProxyDependentSection(
        sectionName: String,
        render: () -> Unit,
    ) {
        val proxyFailure = proxyAvailability.failure()
        if (proxyFailure == null) {
            render()
            return
        }
        eventBus.emit(
            Event.Status.SectionUnavailable(
                sectionName,
                proxyFailureText(proxyFailure),
            ),
        )
    }

    /** Why every section and kit endpoint that needs the private Kubernetes API is not shown. */
    private fun proxyFailureText(proxyFailure: Throwable): String = "SOCKS proxy could not be established: ${proxyFailure.message}"

    /**
     * Display cluster overview section
     */
    private fun displayClusterSection(cluster: ClusterInfo) {
        println(
            """
                |
                |=== CLUSTER STATUS ===
                |Cluster ID: ${cluster.clusterId}
                |Name: ${cluster.name}
                |Created: ${cluster.createdAt}
                |Infrastructure: ${cluster.infrastructureStatus}
            """.trimMargin(),
        )
    }

    /**
     * Display nodes section with live instance state from EC2
     */
    private fun displayNodesSection(nodes: NodesInfo) {
        val sb = StringBuilder()
        sb.appendLine("")
        sb.appendLine("=== NODES ===")

        fun formatGroup(
            header: String,
            group: List<NodeInfo>,
        ) {
            if (group.isEmpty()) return
            sb.appendLine("")
            sb.appendLine("$header:")
            group.forEach { node ->
                sb.appendLine(
                    "  %-12s %-20s %-16s %-16s %-12s %s".format(
                        node.alias,
                        node.instanceId.ifEmpty { "(no id)" },
                        "${node.publicIp} (public)",
                        "${node.privateIp} (private)",
                        node.availabilityZone,
                        (node.state ?: "UNKNOWN").uppercase(),
                    ),
                )
            }
        }
        formatGroup("DATABASE NODES", nodes.database)
        formatGroup("APP NODES", nodes.app)
        formatGroup("CONTROL NODES", nodes.control)
        println(sb.toString().trimEnd())
    }

    /**
     * Display networking section
     */
    private fun displayNetworkingSection(networking: NetworkingInfo?) {
        if (networking == null) {
            eventBus.emit(Event.Status.NoInfrastructureData)
            return
        }

        println(
            """
                |
                |=== NETWORKING ===
                |VPC:              ${networking.vpcId}
                |Internet Gateway: ${networking.internetGatewayId ?: "(none)"}
                |Subnets:          ${networking.subnetIds.joinToString(", ")}
                |Route Tables:     ${networking.routeTableId ?: "(default)"}
            """.trimMargin(),
        )
    }

    /**
     * Display security group rules section
     */
    private fun displaySecurityGroupSection(report: StatusReport) {
        val securityGroup = report.response.securityGroup
        if (securityGroup == null) {
            println("\n=== SECURITY GROUP ===\n(no security group)")
            return
        }

        val inbound = securityGroup.inboundRules
        val outbound = securityGroup.outboundRules
        if (inbound == null || outbound == null) {
            eventBus.emit(Event.Status.SecurityGroupFetchFailed(securityGroup.securityGroupId, report.securityGroupError))
            return
        }

        val sb = StringBuilder()
        sb.appendLine("")
        sb.appendLine("=== SECURITY GROUP ===")
        sb.appendLine("Security Group: ${securityGroup.securityGroupId} (${securityGroup.name})")

        fun formatRules(rules: List<SecurityGroupRuleResponse>) {
            if (rules.isEmpty()) {
                sb.appendLine("  (none)")
                return
            }
            rules.forEach { rule ->
                val ports =
                    when {
                        rule.fromPort == null -> "all"
                        rule.fromPort == rule.toPort -> "${rule.fromPort}"
                        else -> "${rule.fromPort}-${rule.toPort}"
                    }
                val cidrs = rule.cidrBlocks.joinToString(", ")
                val desc = rule.description?.let { " ($it)" } ?: ""
                sb.appendLine("  ${rule.protocol}:$ports  $cidrs$desc")
            }
        }
        sb.appendLine("")
        sb.appendLine("Inbound Rules:")
        formatRules(inbound)
        sb.appendLine("")
        sb.appendLine("Outbound Rules:")
        formatRules(outbound)
        println(sb.toString().trimEnd())
    }

    /**
     * Display Spark/EMR cluster section
     */
    private fun displaySparkClusterSection(spark: SparkInfo?) {
        if (spark == null) {
            println("\n=== SPARK CLUSTER ===\n(no Spark cluster configured)")
            return
        }

        println(
            """
                |
                |=== SPARK CLUSTER ===
                |Cluster ID:  ${spark.clusterId}
                |Name:        ${spark.clusterName}
                |State:       ${spark.state}
                |Master DNS:  ${spark.masterPublicDns ?: "(not available)"}
            """.trimMargin(),
        )
    }

    /**
     * Display OpenSearch domain section
     */
    private fun displayOpenSearchSection(openSearch: OpenSearchInfo?) {
        if (openSearch == null) {
            println("\n=== OPENSEARCH ===\n(no OpenSearch domain configured)")
            return
        }

        println(
            """
                |
                |=== OPENSEARCH ===
                |Domain:     ${openSearch.domainName} (${openSearch.domainId})
                |State:      ${openSearch.state}
                |Endpoint:   ${openSearch.endpoint ?: "(not available)"}
                |Dashboards: ${openSearch.dashboardsEndpoint ?: "(not available)"}
            """.trimMargin(),
        )
    }

    /**
     * Display S3 bucket section
     */
    private fun displayS3BucketSection(
        s3: S3Info?,
        state: ClusterState,
    ) {
        if (s3 == null) {
            println("\n=== S3 BUCKET ===\n(no S3 bucket configured)")
            return
        }

        println(
            """
                |
                |=== S3 BUCKET ===
                |Bucket:    ${s3.bucket}
                |Fullpath:     ${s3.fullpath}
                |  Cassandra: ${s3.paths.cassandra}
                |  ClickHouse: ${s3.paths.clickhouse}
                |  Spark: ${s3.paths.spark}
                |  EMR Logs: ${s3.paths.emrLogs}
                |Observability:
                |  Tenant:    ${ObservabilityStore.from(state).tenant}
                |  Traces:    ${s3.paths.traces}
                |  Profiles:  ${s3.paths.profiles}
                |  Metrics:   ${s3.paths.metrics}
                |  Logs:      ${s3.paths.logs}
                |  Annotations: ${s3.paths.annotations}
            """.trimMargin(),
        )
    }

    /**
     * Display installed kits with a running indicator, and under each running kit the endpoints
     * its `kit.yaml` declares, from the collected [kits]: each where it is served, and a line that
     * says why when one is not (no pod serves it, or the proxy is down). A stopped kit shows none: its
     * server is gone even when other pods of the kit, such as ClickHouse Keeper, still run.
     * Reads [WorkspaceKitScanner], the same discovery rule dynamic subcommand registration uses.
     * Registration can still drop a discovered kit afterwards — on a name collision, or when
     * building its command group fails — so this listing may name a kit that has no subcommand.
     */
    private fun displayKitsSection(kits: List<KitAccess>) {
        val installedKits = workspaceKitScanner.discover().sortedBy { it.name }

        if (installedKits.isEmpty()) return

        val runningKits = clusterState.runningKits
        val endpointsByKit = kits.associateBy { it.name }
        println()
        println("=== KITS ===")
        installedKits.forEach { kitDir ->
            val running = kitDir.name in runningKits
            println("  ${if (running) "✓" else "○"} ${kitDir.name}")
            val access = if (running) endpointsByKit[kitDir.name] else null
            access?.endpointsUnavailable?.let { println("    (endpoints unavailable: $it)") }
            access?.endpoints?.forEach { println("  " + Event.Kit.EndpointAddress(it.name, it.type, it.address).displayLine()) }
        }
    }

    /**
     * Display stress jobs section
     */
    private fun displayStressJobsSection(report: StatusReport) {
        report.stressJobsError?.let {
            eventBus.emit(Event.Status.StressJobsError(it))
            return
        }
        val stress = report.response.stressJobs
        if (stress == null) {
            eventBus.emit(Event.Status.StressJobsUnavailable)
            return
        }

        val sb = StringBuilder()
        sb.appendLine("")
        sb.appendLine("=== STRESS JOBS ===")
        if (stress.jobs.isEmpty()) {
            sb.appendLine("  (no jobs)")
        } else {
            sb.appendLine("  %-40s %-12s %-12s %s".format("NAME", "STATUS", "COMPLETIONS", "AGE"))
            stress.jobs.forEach { job ->
                sb.appendLine(
                    "  %-40s %-12s %-12s %s".format(
                        job.name.take(POD_NAME_MAX_LENGTH),
                        job.status,
                        job.completions,
                        job.age,
                    ),
                )
            }
        }
        println(sb.toString().trimEnd())
    }

    /**
     * Display observability stack access information
     */
    private fun displayObservabilitySection(accessInfo: AccessInfo?) {
        val observability = accessInfo?.observability ?: return
        println(
            """

Observability:
  Grafana:         ${observability.grafana}
  Mimir:           ${observability.mimir}/prometheus
  Loki:            ${observability.loki}
  Tempo:           ${observability.tempo}
  Pyroscope:       ${observability.pyroscope}
""",
        )
    }

    /**
     * Display S3Manager access information if K3s is initialized
     */
    private fun displayS3ManagerSection(accessInfo: AccessInfo?) {
        val s3Manager = accessInfo?.s3Manager ?: return
        println(
            """

S3 Manager:
  Web UI: ${s3Manager.ui}""",
        )
    }

    /**
     * Display Cassandra version section
     */
    private fun displayCassandraVersionSection(version: CassandraVersionInfo?) {
        if (version == null) {
            eventBus.emit(Event.Status.CassandraNoNodes)
            return
        }
        println(renderCassandraVersion(version))
    }
}

/**
 * The `=== CASSANDRA VERSION ===` section. A cluster whose db hosts report different versions
 * says so and lists each host's version; the first host's version alone would hide the mismatch.
 */
internal fun renderCassandraVersion(version: CassandraVersionInfo): String =
    buildString {
        append("\n=== CASSANDRA VERSION ===\n")
        if (version.mixed) {
            append("Version (${version.source}): MIXED, the db hosts run different versions\n")
            version.hosts.forEach { append("  ${it.host}: ${it.version}\n") }
        } else {
            append("Version (${version.source}): ${version.version}\n")
        }
        if (version.unanswered.isNotEmpty()) {
            append("No answer from: ${version.unanswered.joinToString(", ")}\n")
        }
    }.trimEnd()

/**
 * Thrown by [Status] after it renders a degraded report, so the command still exits non-zero.
 *
 * Rendering happens before this is thrown — `Status.execute` renders every section, guarding
 * only the tunnel-dependent ones via `renderProxyDependentSection`, and throws this only at the
 * end — so a caller reading only the output still sees everything that could be observed, while a
 * caller reading only the exit code can never mistake a partial report for a healthy cluster.
 * The proxy failure that forced the degradation is carried as this exception's cause.
 */
class StatusDegradedException(
    proxyFailure: Throwable,
) : Exception("status degraded: SOCKS proxy could not be established", proxyFailure)
