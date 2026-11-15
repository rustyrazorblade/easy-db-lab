package com.rustyrazorblade.easydblab.server

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Version
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.EMRClusterState
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.NodeState
import com.rustyrazorblade.easydblab.configuration.OpenSearchClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kubernetes.KubernetesJob
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import com.rustyrazorblade.easydblab.kubernetes.WorkspaceKubeconfig
import com.rustyrazorblade.easydblab.providers.aws.InstanceDetails
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupDetails
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupRuleInfo
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.KitEndpointAddresses
import com.rustyrazorblade.easydblab.services.StressJobService
import com.rustyrazorblade.easydblab.services.WorkspaceKitScanner
import com.rustyrazorblade.easydblab.services.aws.DomainState
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.EMRService
import com.rustyrazorblade.easydblab.services.aws.OpenSearchDomainResult
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.time.Duration

/**
 * The one status collection that `status` and `/status` both render. These tests pin what the two
 * used to disagree about (the S3 paths, the OpenSearch endpoint, a failed security group fetch) and
 * the choices that depend on where a fact comes from (the data bucket against the account bucket,
 * whether the Kubernetes API is reachable, whether a node is running).
 */
class ClusterStatusCollectorTest : BaseKoinTest() {
    private lateinit var ec2: EC2InstanceService
    private lateinit var vpc: VpcService
    private lateinit var k8s: K8sService
    private lateinit var emr: EMRService
    private lateinit var openSearch: OpenSearchService
    private lateinit var stress: StressJobService
    private lateinit var remoteOps: RemoteOperationsService

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { ec2 }
                single<VpcService> { vpc }
                factory<K8sService> { k8s }
                single { emr }
                single { openSearch }
                single<StressJobService> { stress }
                single<RemoteOperationsService> { remoteOps }
                single { WorkspaceKitScanner(get()) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        ec2 = mock()
        vpc = mock()
        k8s = mock()
        emr = mock()
        openSearch = mock()
        stress = mock()
        remoteOps = mock()
        // A mock returns null for kotlin.Result, so a listing that nothing in the test cares about is stubbed
        whenever(k8s.listPods()).thenReturn(Result.success(emptyList()))
    }

    private fun db(
        alias: String = "db0",
        id: String = "i-db0",
    ) = ClusterHost("54.0.0.1", "10.0.0.1", alias, "us-west-2a", id)

    private fun state(
        dataBucket: String = "data-bucket",
        hosts: Map<ServerType, List<ClusterHost>> = emptyMap(),
        openSearchDomain: OpenSearchClusterState? = null,
        emrCluster: EMRClusterState? = null,
    ) = ClusterState(
        name = "lab",
        clusterId = "abc123",
        versions = null,
        default = NodeState(version = "5.0.2"),
        s3Bucket = "account-bucket",
        dataBucket = dataBucket,
        hosts = hosts,
        infrastructure = InfrastructureState(vpcId = "vpc-1", region = "us-west-2", subnetIds = listOf("s"), securityGroupId = "sg-1"),
        openSearchDomain = openSearchDomain,
        emrCluster = emrCluster,
    )

    private fun collector() = ClusterStatusCollector()

    private fun runningNode(id: String = "i-db0") = InstanceDetails(id, "running", "54.0.0.1", "10.0.0.1", "us-west-2a", "m5.large")

    @Test
    fun `ClickHouse and Spark paths are in the data bucket, Cassandra and EMR logs under the cluster prefix of the account bucket`() {
        val paths = checkNotNull(collector().collect(state()).response.s3).paths

        assertThat(paths.clickhouse).isEqualTo("s3://data-bucket/clickhouse")
        assertThat(paths.spark).isEqualTo("s3://data-bucket/spark")
        assertThat(paths.cassandra).isEqualTo("s3://account-bucket/clusters/lab-abc123/cassandra")
        assertThat(paths.emrLogs).isEqualTo("s3://account-bucket/clusters/lab-abc123/spark/emr-logs")
    }

    @Test
    fun `a cluster with no data bucket keeps its ClickHouse and Spark paths in the account bucket`() {
        val paths = checkNotNull(collector().collect(state(dataBucket = "")).response.s3).paths

        assertThat(paths.clickhouse).isEqualTo("s3://account-bucket/clickhouse")
        assertThat(paths.spark).isEqualTo("s3://account-bucket/spark")
    }

    @Test
    fun `the OpenSearch endpoint carries https and the state is the live state's name`() {
        whenever(openSearch.describeDomain("dom")).thenReturn(
            OpenSearchDomainResult(domainName = "dom", domainId = "id", state = DomainState.ACTIVE),
        )

        val info =
            checkNotNull(
                collector()
                    .collect(
                        state(openSearchDomain = OpenSearchClusterState("dom", "id", endpoint = "search.example.com", state = "Creating")),
                    ).response.opensearch,
            )

        assertThat(info.endpoint).isEqualTo("https://search.example.com")
        assertThat(info.state).isEqualTo("ACTIVE")
    }

    @Test
    fun `OpenSearch and Spark fall back to the recorded state when AWS cannot be asked`() {
        whenever(openSearch.describeDomain(any())).thenThrow(RuntimeException("throttled"))
        whenever(emr.getClusterStatus(any())).thenThrow(RuntimeException("throttled"))

        val response =
            collector()
                .collect(
                    state(
                        openSearchDomain = OpenSearchClusterState("dom", "id", state = "Creating"),
                        emrCluster = EMRClusterState("j-1", "emr", state = "STARTING"),
                    ),
                ).response

        assertThat(response.opensearch?.state).isEqualTo("Creating")
        assertThat(response.spark?.state).isEqualTo("STARTING")
    }

    @Test
    fun `a security group that cannot be described stays in the report with no rules and the reason`() {
        whenever(vpc.describeSecurityGroup("sg-1")).thenThrow(RuntimeException("UnauthorizedOperation"))

        val report = collector().collect(state())

        val group = checkNotNull(report.response.securityGroup)
        assertThat(group.securityGroupId).isEqualTo("sg-1")
        assertThat(group.inboundRules).isNull()
        assertThat(group.outboundRules).isNull()
        assertThat(report.securityGroupError).contains("UnauthorizedOperation")
    }

    @Test
    fun `a described security group reports its rules and no error`() {
        whenever(vpc.describeSecurityGroup("sg-1")).thenReturn(
            SecurityGroupDetails("sg-1", "lab-sg", "d", "vpc-1", inboundRules = emptyList(), outboundRules = emptyList()),
        )

        val report = collector().collect(state())

        assertThat(report.response.securityGroup?.name).isEqualTo("lab-sg")
        assertThat(report.response.securityGroup?.inboundRules).isEmpty()
        assertThat(report.securityGroupError).isNull()
    }

    @Test
    fun `the live Cassandra version is read only when a node is running`() {
        val hosts = mapOf(ServerType.Cassandra to listOf(db()))
        whenever(remoteOps.getRemoteVersion(any(), any())).thenReturn(Version.fromString("5.0.9"))

        whenever(ec2.describeInstances(any())).thenReturn(listOf(runningNode().copy(state = "stopped")))
        val whenStopped = collector().collect(state(hosts = hosts)).response.cassandraVersion
        whenever(ec2.describeInstances(any())).thenReturn(listOf(runningNode()))
        val whenRunning = collector().collect(state(hosts = hosts)).response.cassandraVersion

        assertThat(whenStopped).isEqualTo(CassandraVersionInfo("5.0.2", "cached"))
        assertThat(whenRunning).isEqualTo(CassandraVersionInfo("5.0.9", "live"))
    }

    @Test
    fun `no Cassandra nodes means no version section`() {
        assertThat(collector().collect(state()).response.cassandraVersion).isNull()
    }

    @Test
    fun `stress jobs are listed with their age, left out while the Kubernetes API is unreachable, and an error carries its message`() {
        WorkspaceKubeconfig.file(context.workingDirectory).writeText("kubeconfig")
        whenever(
            stress.listJobs(),
        ).thenReturn(Result.success(listOf(KubernetesJob("default", "job-1", "Running", "0/1", Duration.ofMinutes(90)))))

        val listed = collector().collect(state()).response.stressJobs
        val unreachable = collector().collect(state(), kubernetesReachable = false)

        assertThat(listed).isEqualTo(StressInfo(listOf(StressJobInfo("job-1", "Running", "0/1", "1h"))))
        assertThat(unreachable.response.stressJobs).isNull()
        assertThat(unreachable.stressJobsError).isNull()

        whenever(stress.listJobs()).thenReturn(Result.failure(IllegalStateException("api down")))
        val failed = collector().collect(state())
        assertThat(failed.response.stressJobs).isNull()
        assertThat(failed.stressJobsError).isEqualTo("api down")
    }

    @Test
    fun `pods are listed only when asked for`() {
        WorkspaceKubeconfig.file(context.workingDirectory).writeText("kubeconfig")
        whenever(k8s.listPods()).thenReturn(Result.success(emptyList()))

        assertThat(collector().collect(state()).response.kubernetes).isEqualTo(KubernetesInfo(emptyList()))
        assertThat(collector().collect(state(), includePods = false).response.kubernetes).isNull()
    }

    @Test
    fun `access info needs the control node and a kubeconfig`() {
        val hosts = mapOf(ServerType.Control to listOf(ClusterHost("54.0.0.2", "10.0.0.2", "control0", "us-west-2a", "i-c")))

        assertThat(collector().collect(state(hosts = hosts)).response.accessInfo).isNull()

        WorkspaceKubeconfig.file(context.workingDirectory).writeText("kubeconfig")
        val access = checkNotNull(collector().collect(state(hosts = hosts)).response.accessInfo)
        assertThat(access.observability?.grafana).isEqualTo("http://10.0.0.2:${Constants.K8s.GRAFANA_PORT}")
        assertThat(access.s3Manager?.ui).contains("/buckets/account-bucket/clusters/lab-abc123/")
    }

    @Test
    fun `a cluster with no account bucket has access info with no S3 manager`() {
        val hosts = mapOf(ServerType.Control to listOf(ClusterHost("54.0.0.2", "10.0.0.2", "control0", "us-west-2a", "i-c")))
        WorkspaceKubeconfig.file(context.workingDirectory).writeText("kubeconfig")

        val access = checkNotNull(collector().collect(state(hosts = hosts).also { it.s3Bucket = null }).response.accessInfo)

        assertThat(access.observability).isNotNull
        assertThat(access.s3Manager).isNull()
    }

    @Test
    fun `nodes are grouped by server type with the uppercased live state and instance type`() {
        val hosts =
            mapOf(
                ServerType.Cassandra to listOf(db(), db("db1", "i-db1")),
                ServerType.Stress to listOf(ClusterHost("54.0.0.3", "10.0.0.3", "app0", "us-west-2b", "i-app0")),
                ServerType.Control to listOf(ClusterHost("54.0.0.2", "10.0.0.2", "control0", "us-west-2a", "i-c")),
            )
        whenever(ec2.describeInstances(any())).thenReturn(listOf(runningNode("i-db0"), runningNode("i-app0").copy(state = "stopped")))

        val nodes = collector().collect(state(hosts = hosts)).response.nodes

        assertThat(nodes.database.map { it.alias }).containsExactly("db0", "db1")
        assertThat(nodes.database[0]).extracting("state", "instanceType").containsExactly("RUNNING", "m5.large")
        assertThat(nodes.database[1].state).isNull()
        assertThat(nodes.app.single().state).isEqualTo("STOPPED")
        assertThat(nodes.control.single().alias).isEqualTo("control0")
    }

    @Test
    fun `when EC2 cannot be asked the nodes are still listed, with no state`() {
        whenever(ec2.describeInstances(any())).thenThrow(RuntimeException("throttled"))

        val nodes = collector().collect(state(hosts = mapOf(ServerType.Cassandra to listOf(db())))).response.nodes

        assertThat(nodes.database.single().alias).isEqualTo("db0")
        assertThat(nodes.database.single().state).isNull()
    }

    @Test
    fun `security group rules keep their protocol, ports, cidrs and description`() {
        whenever(vpc.describeSecurityGroup("sg-1")).thenReturn(
            SecurityGroupDetails(
                "sg-1",
                "lab-sg",
                "d",
                "vpc-1",
                inboundRules = listOf(SecurityGroupRuleInfo("tcp", 9042, 9042, listOf("10.0.0.0/16", "10.1.0.0/16"), "CQL")),
                outboundRules = listOf(SecurityGroupRuleInfo("-1", null, null, listOf("0.0.0.0/0"), null)),
            ),
        )

        val group = checkNotNull(collector().collect(state()).response.securityGroup)

        assertThat(
            group.inboundRules,
        ).containsExactly(SecurityGroupRuleResponse("tcp", 9042, 9042, listOf("10.0.0.0/16", "10.1.0.0/16"), "CQL"))
        assertThat(group.outboundRules).containsExactly(SecurityGroupRuleResponse("-1", null, null, listOf("0.0.0.0/0"), null))
    }

    @ParameterizedTest
    @CsvSource("0, 0m", "5, 5m", "59, 59m", "60, 1h", "1439, 23h", "1440, 24h")
    fun `a job's age is minutes under an hour and hours up to a day`(
        minutes: Long,
        expected: String,
    ) {
        assertThat(stressAge(Duration.ofMinutes(minutes))).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource("25, 1d", "47, 1d", "48, 2d", "100, 4d")
    fun `a job older than a day is shown in whole days`(
        hours: Long,
        expected: String,
    ) {
        assertThat(stressAge(Duration.ofHours(hours))).isEqualTo(expected)
    }

    private fun stressAge(age: Duration): String {
        WorkspaceKubeconfig.file(context.workingDirectory).writeText("kubeconfig")
        whenever(stress.listJobs()).thenReturn(Result.success(listOf(KubernetesJob("default", "job", "Running", "0/1", age))))
        return checkNotNull(collector().collect(state()).response.stressJobs).jobs.single().age
    }

    @Test
    fun `when EC2 cannot be asked the live version is still tried, and a failing or unconfigured node falls back to cached`() {
        val hosts = mapOf(ServerType.Cassandra to listOf(db()))
        whenever(ec2.describeInstances(any())).thenThrow(RuntimeException("throttled"))

        whenever(remoteOps.getRemoteVersion(any(), any())).thenReturn(Version.fromString("5.0.9"))
        val live = collector().collect(state(hosts = hosts)).response.cassandraVersion
        whenever(remoteOps.getRemoteVersion(any(), any())).thenThrow(RuntimeException("connection refused"))
        val unreachable = collector().collect(state(hosts = hosts)).response.cassandraVersion
        whenever(remoteOps.getRemoteVersion(any(), any())).thenReturn(Version.fromString("current"))
        val unconfigured = collector().collect(state(hosts = hosts)).response.cassandraVersion

        assertThat(live).isEqualTo(CassandraVersionInfo("5.0.9", "live"))
        assertThat(unreachable).isEqualTo(CassandraVersionInfo("5.0.2", "cached"))
        assertThat(unconfigured).isEqualTo(CassandraVersionInfo("5.0.2", "cached"))
    }

    @Test
    fun `pods are listed with their age and a failed listing leaves the section out`() {
        WorkspaceKubeconfig.file(context.workingDirectory).writeText("kubeconfig")
        whenever(k8s.listPods()).thenReturn(
            Result.success(listOf(KubernetesPod("default", "web-1", "Running", "1/1", 2, Duration.ofHours(3)))),
        )
        val listed = collector().collect(state()).response.kubernetes
        whenever(k8s.listPods()).thenReturn(Result.failure(IllegalStateException("api down")))
        val failed = collector().collect(state()).response.kubernetes

        assertThat(listed).isEqualTo(KubernetesInfo(listOf(PodInfo("default", "web-1", "1/1", "Running", 2, "3h"))))
        assertThat(failed).isNull()
    }

    @Test
    fun `running kits are the installed kits recorded as running, sorted, with unreadable ones saying so`() {
        File(
            context.workingDirectory,
            "zeta",
        ).also { it.mkdirs() }.resolve(Constants.Kit.CONFIG_FILE).writeText("name: [broken\nendpoints: {")
        File(
            context.workingDirectory,
            "alpha",
        ).also { it.mkdirs() }.resolve(Constants.Kit.CONFIG_FILE).writeText("name: [broken\nendpoints: {")
        File(
            context.workingDirectory,
            "stopped",
        ).also { it.mkdirs() }.resolve(Constants.Kit.CONFIG_FILE).writeText("name: [broken\nendpoints: {")
        val running = state().also { it.runningKits = setOf("zeta", "alpha", "never-installed") }

        val kits = collector().runningKits(running)

        assertThat(kits.map { it.name }).containsExactly("alpha", "zeta")
        assertThat(kits).allSatisfy {
            assertThat(it.endpoints).isEmpty()
            assertThat(it.endpointsUnavailable).isEqualTo(KitEndpointAddresses.UNREADABLE_DESCRIPTOR)
        }
    }
}
