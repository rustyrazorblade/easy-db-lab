package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.OpenSearchClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class TemplateVariablesTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single { TemplateService(get(), get()) }
            },
        )

    private lateinit var templateService: TemplateService

    @BeforeEach
    fun setup() {
        templateService = getKoin().get()
    }

    private fun controlHost() =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.1.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-control",
        )

    private fun dbHost(n: Int) =
        ClusterHost(
            publicIp = "54.2.0.$n",
            privateIp = "10.0.2.$n",
            alias = "db$n",
            availabilityZone = "us-west-2a",
            instanceId = "i-db$n",
        )

    private fun appHost(n: Int) =
        ClusterHost(
            publicIp = "54.3.0.$n",
            privateIp = "10.0.3.$n",
            alias = "app$n",
            availabilityZone = "us-west-2a",
            instanceId = "i-app$n",
        )

    private fun stateWith(
        dbCount: Int = 3,
        appCount: Int = 2,
        bucketName: String = "my-bucket",
        region: String = "us-west-2",
        cidr: String? = null,
    ): ClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = bucketName,
            initConfig = InitConfig(region = region, name = "test-cluster", cidr = cidr),
            hosts =
                buildMap {
                    put(ServerType.Control, listOf(controlHost()))
                    if (dbCount > 0) put(ServerType.Cassandra, (0 until dbCount).map { dbHost(it) })
                    if (appCount > 0) put(ServerType.Stress, (0 until appCount).map { appHost(it) })
                },
        )

    @Test
    fun `from builds correct db and app counts`() {
        val state = stateWith(dbCount = 3, appCount = 2)
        val vars = TemplateVariables.from(state = state, kitName = "clickhouse", storageSize = "100Gi")

        assertThat(vars.dbNodeCount).isEqualTo(3)
        assertThat(vars.appNodeCount).isEqualTo(2)
    }

    @Test
    fun `from builds comma-joined db node IPs`() {
        val state = stateWith(dbCount = 3)
        val vars = TemplateVariables.from(state = state, kitName = "presto", storageSize = "0Gi")

        assertThat(vars.dbNodeIps).isEqualTo("10.0.2.0,10.0.2.1,10.0.2.2")
    }

    @Test
    fun `from produces empty dbNodeIps when no db nodes`() {
        val state = stateWith(dbCount = 0)
        val vars = TemplateVariables.from(state = state, kitName = "presto", storageSize = "0Gi")

        assertThat(vars.dbNodeIps).isEmpty()
    }

    @Test
    fun `from builds comma-joined app node IPs`() {
        val state = stateWith(appCount = 2)
        val vars = TemplateVariables.from(state = state, kitName = "presto", storageSize = "0Gi")

        assertThat(vars.appNodeIps).isEqualTo("10.0.3.0,10.0.3.1")
    }

    @Test
    fun `from produces empty appNodeIps when no app nodes`() {
        val state = stateWith(appCount = 0)
        val vars = TemplateVariables.from(state = state, kitName = "presto", storageSize = "0Gi")

        assertThat(vars.appNodeIps).isEmpty()
    }

    @Test
    fun `toMap includes APP_NODE_IPS key`() {
        val state = stateWith(appCount = 2)
        val vars = TemplateVariables.from(state = state, kitName = "presto", storageSize = "0Gi")

        assertThat(vars.toMap()).containsKey("APP_NODE_IPS")
        assertThat(vars.toMap()["APP_NODE_IPS"]).isEqualTo("10.0.3.0,10.0.3.1")
    }

    @Test
    fun `from sets control host public IP`() {
        val state = stateWith()
        val vars = TemplateVariables.from(state = state, kitName = "clickhouse", storageSize = "100Gi")

        assertThat(vars.controlHostPublic).isEqualTo("54.1.2.3")
    }

    @Test
    fun `from sets control host private IP`() {
        val state = stateWith()
        val vars = TemplateVariables.from(state = state, kitName = "clickhouse", storageSize = "100Gi")

        assertThat(vars.controlHostPrivate).isEqualTo("10.0.1.1")
    }

    @Test
    fun `from sets accountBucket from s3Bucket`() {
        val state = stateWith(bucketName = "account-level-bucket")
        val vars = TemplateVariables.from(state = state, kitName = "clickhouse", storageSize = "100Gi")

        assertThat(vars.accountBucket).isEqualTo("account-level-bucket")
    }

    @Test
    fun `toMap includes ACCOUNT_BUCKET key`() {
        val state = stateWith(bucketName = "my-account-bucket")
        val vars = TemplateVariables.from(state = state, kitName = "clickhouse", storageSize = "100Gi")

        assertThat(vars.toMap()).containsKey("ACCOUNT_BUCKET")
        assertThat(vars.toMap()["ACCOUNT_BUCKET"]).isEqualTo("my-account-bucket")
    }

    @Test
    fun `from sets storageClassWfc to the WFC constant`() {
        val state = stateWith()
        val vars = TemplateVariables.from(state = state, kitName = "clickhouse", storageSize = "100Gi")

        assertThat(vars.storageClassWfc).isEqualTo(Constants.K8s.LOCAL_STORAGE_WFC_CLASS)
    }

    @Test
    fun `renderKitTemplate substitutes all standard variables`() {
        val state = stateWith(bucketName = "test-bucket", region = "us-east-1")
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val vars =
            TemplateVariables.from(
                state = state,
                kitName = "mydb",
                storageSize = "50Gi",
            )
        val template =
            """
            cluster: __CLUSTER_NAME__
            workload: __KIT_NAME__
            size: __STORAGE_SIZE__
            dbNodes: __DB_NODE_COUNT__
            appNodes: __APP_NODE_COUNT__
            bucket: __BUCKET_NAME__
            region: __REGION__
            storageClass: __STORAGE_CLASS_WFC__
            controlHost: __CONTROL_HOST__
            controlHostPublic: __CONTROL_HOST_PUBLIC__
            controlHostPrivate: __CONTROL_HOST_PRIVATE__
            kubeconfig: __KUBECONFIG__
            """.trimIndent()

        val rendered = templateService.renderKitTemplate(template, vars)

        assertThat(rendered).contains("cluster: test-cluster")
        assertThat(rendered).contains("workload: mydb")
        assertThat(rendered).contains("size: 50Gi")
        assertThat(rendered).contains("dbNodes: 3")
        assertThat(rendered).contains("appNodes: 2")
        assertThat(rendered).contains("bucket: test-bucket")
        assertThat(rendered).contains("region: us-east-1")
        assertThat(rendered).contains("storageClass: ${Constants.K8s.LOCAL_STORAGE_WFC_CLASS}")
        assertThat(rendered).contains("controlHost: 54.1.2.3")
        assertThat(rendered).contains("controlHostPublic: 54.1.2.3")
        assertThat(rendered).contains("controlHostPrivate: 10.0.1.1")
        assertThat(rendered).contains("kubeconfig: ${Constants.K3s.LOCAL_KUBECONFIG}")
        assertThat(rendered).doesNotContain("__")
    }

    @Test
    fun `renderKitTemplate calls onUnresolved for leftover placeholders`() {
        val state = stateWith()
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val vars = TemplateVariables.from(state = state, kitName = "mydb", storageSize = "50Gi")
        val template = "replicas: __REPLICAS__\nshards: __SHARDS__"

        val unresolved = mutableListOf<String>()
        templateService.renderKitTemplate(template, vars) { unresolved.addAll(it) }

        assertThat(unresolved).containsExactlyInAnyOrder("REPLICAS", "SHARDS")
    }

    @Test
    fun `renderKitTemplate does not call onUnresolved when all variables are resolved`() {
        val state = stateWith()
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val vars = TemplateVariables.from(state = state, kitName = "mydb", storageSize = "50Gi")
        val template = "workload: __KIT_NAME__"

        var called = false
        templateService.renderKitTemplate(template, vars) { called = true }

        assertThat(called).isFalse()
    }

    @Test
    fun `resolveEasyDbLabExec returns fixed path when file exists`() {
        val fixed = File(tempDir, "easy-db-lab")
        fixed.createNewFile()

        assertThat(TemplateVariables.resolveEasyDbLabExec(fixed)).isEqualTo(fixed.absolutePath)
    }

    @Test
    fun `resolveEasyDbLabExec falls back to apphome when fixed path does not exist`() {
        val missing = File(tempDir, "nonexistent-easy-db-lab")
        System.setProperty("easydblab.apphome", "/opt/easy-db-lab")

        try {
            val result = TemplateVariables.resolveEasyDbLabExec(missing)
            assertThat(result).isEqualTo("/opt/easy-db-lab/bin/easy-db-lab")
        } finally {
            System.clearProperty("easydblab.apphome")
        }
    }

    @Test
    fun `from produces empty runningKits when none started`() {
        val state = stateWith()
        val vars = TemplateVariables.from(state = state, kitName = "presto", storageSize = "0Gi")

        assertThat(vars.runningKits).isEmpty()
    }

    @Test
    fun `from builds comma-joined runningKits from state`() {
        val state = stateWith().also { it.runningKits = mutableSetOf("cassandra", "clickhouse") }
        val vars = TemplateVariables.from(state = state, kitName = "presto", storageSize = "0Gi")

        assertThat(vars.runningKits.split(",")).containsExactlyInAnyOrder("cassandra", "clickhouse")
    }

    @Test
    fun `toMap includes RUNNING_KITS key`() {
        val state = stateWith().also { it.runningKits = mutableSetOf("cassandra") }
        val vars = TemplateVariables.from(state = state, kitName = "presto", storageSize = "0Gi")

        assertThat(vars.toMap()).containsKey("RUNNING_KITS")
        assertThat(vars.toMap()["RUNNING_KITS"]).isEqualTo("cassandra")
    }

    @Test
    fun `from uses vpcCidr from initConfig cidr`() {
        val vars = TemplateVariables.from(state = stateWith(cidr = "10.1.0.0/16"), kitName = "clickhouse", storageSize = "100Gi")

        assertThat(vars.vpcCidr).isEqualTo("10.1.0.0/16")
    }

    @Test
    fun `from defaults vpcCidr to DEFAULT_CIDR when cidr not specified`() {
        val vars = TemplateVariables.from(state = stateWith(), kitName = "clickhouse", storageSize = "100Gi")

        assertThat(vars.vpcCidr).isEqualTo(Constants.Vpc.DEFAULT_CIDR)
    }

    @Test
    fun `toMap includes VPC_CIDR key`() {
        val vars = TemplateVariables.from(state = stateWith(), kitName = "clickhouse", storageSize = "100Gi")

        assertThat(vars.toMap()).containsKey("VPC_CIDR")
    }

    @Test
    fun `from produces empty opensearchEndpoint when no opensearch domain`() {
        val vars = TemplateVariables.from(state = stateWith(), kitName = "trino", storageSize = "0Gi")

        assertThat(vars.opensearchEndpoint).isEmpty()
    }

    @Test
    fun `from sets opensearchEndpoint from openSearchDomain endpoint`() {
        val state =
            stateWith().also {
                it.openSearchDomain =
                    OpenSearchClusterState(
                        domainName = "my-domain",
                        domainId = "id-123",
                        endpoint = "search-my-domain-xyz.us-east-1.es.amazonaws.com",
                    )
            }
        val vars = TemplateVariables.from(state = state, kitName = "trino", storageSize = "0Gi")

        assertThat(vars.opensearchEndpoint).isEqualTo("search-my-domain-xyz.us-east-1.es.amazonaws.com")
    }

    @Test
    fun `toMap includes OPENSEARCH_ENDPOINT key`() {
        val state =
            stateWith().also {
                it.openSearchDomain =
                    OpenSearchClusterState(
                        domainName = "my-domain",
                        domainId = "id-123",
                        endpoint = "search-my-domain-xyz.us-east-1.es.amazonaws.com",
                    )
            }
        val vars = TemplateVariables.from(state = state, kitName = "trino", storageSize = "0Gi")

        assertThat(vars.toMap()).containsEntry("OPENSEARCH_ENDPOINT", "search-my-domain-xyz.us-east-1.es.amazonaws.com")
    }
}
