package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.EMRClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.OpenSearchClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.providers.aws.CreatedInstance
import com.rustyrazorblade.easydblab.providers.aws.InstanceCreationConfig
import com.rustyrazorblade.easydblab.services.aws.DomainState
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.InstanceSpec
import com.rustyrazorblade.easydblab.services.aws.OpenSearchDomainResult
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for ClusterProvisioningService.
 */
class ClusterProvisioningServiceTest {
    private lateinit var ec2InstanceService: EC2InstanceService
    private lateinit var emrProvisioningService: EMRProvisioningService
    private lateinit var openSearchService: OpenSearchService
    private lateinit var aws: AWS
    private lateinit var user: User
    private lateinit var eventBus: EventBus
    private lateinit var service: ClusterProvisioningService

    @BeforeEach
    fun setup() {
        ec2InstanceService = mock()
        emrProvisioningService = mock()
        openSearchService = mock()
        aws = mock()
        user = mock()
        eventBus = EventBus()
        whenever(aws.getAccountId()).thenReturn("123456789012")
        whenever(user.region).thenReturn("us-west-2")
        service =
            DefaultClusterProvisioningService(
                ec2InstanceService = ec2InstanceService,
                emrProvisioningService = emrProvisioningService,
                openSearchService = openSearchService,
                aws = aws,
                user = user,
                eventBus = eventBus,
            )
    }

    @Nested
    inner class ProvisionInstances {
        @Test
        fun `should create instances for specs with positive neededCount`() {
            val spec =
                InstanceSpec(
                    serverType = ServerType.Cassandra,
                    configuredCount = 3,
                    existingCount = 0,
                    instanceType = "m5.large",
                    ebsConfig = null,
                )
            val config = createInstanceConfig(specs = listOf(spec))

            setupMockInstanceCreation()

            var createdHosts: List<ClusterHost>? = null
            val result =
                service.provisionInstances(config, emptyMap()) { _, hosts ->
                    createdHosts = hosts
                }

            assertThat(result.errors).isEmpty()
            assertThat(result.hosts[ServerType.Cassandra]).hasSize(3)
            assertThat(createdHosts).hasSize(3)
        }

        @Test
        fun `should skip specs with zero or negative neededCount`() {
            val spec =
                InstanceSpec(
                    serverType = ServerType.Cassandra,
                    configuredCount = 2,
                    existingCount = 3,
                    instanceType = "m5.large",
                    ebsConfig = null,
                )
            val config = createInstanceConfig(specs = listOf(spec))

            val existingHosts =
                mapOf(
                    ServerType.Cassandra to
                        listOf(
                            createClusterHost("i-1", "db0"),
                            createClusterHost("i-2", "db1"),
                            createClusterHost("i-3", "db2"),
                        ),
                )

            val result = service.provisionInstances(config, existingHosts) { _, _ -> }

            verify(ec2InstanceService, never()).createInstances(any())
            assertThat(result.hosts[ServerType.Cassandra]).hasSize(3)
        }

        @Test
        fun `should emit event for existing instances`() {
            val spec =
                InstanceSpec(
                    serverType = ServerType.Cassandra,
                    configuredCount = 3,
                    existingCount = 3,
                    instanceType = "m5.large",
                    ebsConfig = null,
                )
            val config = createInstanceConfig(specs = listOf(spec))

            val emittedEvents = mutableListOf<Event>()
            eventBus.addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        emittedEvents.add(envelope.event)
                    }

                    override fun close() {}
                },
            )

            service.provisionInstances(config, emptyMap()) { _, _ -> }

            assertThat(emittedEvents).anyMatch { event ->
                event is Event.Ec2.ExistingInstancesFound &&
                    event.serverType == "Cassandra" &&
                    event.count == 3
            }
        }

        @Test
        fun `should collect errors when instance creation fails`() {
            val spec =
                InstanceSpec(
                    serverType = ServerType.Cassandra,
                    configuredCount = 3,
                    existingCount = 0,
                    instanceType = "m5.large",
                    ebsConfig = null,
                )
            val config = createInstanceConfig(specs = listOf(spec))

            whenever(ec2InstanceService.createInstances(any()))
                .thenThrow(RuntimeException("Instance creation failed"))

            val result = service.provisionInstances(config, emptyMap()) { _, _ -> }

            assertThat(result.errors).containsKey("Cassandra instances")
            assertThat(result.errors["Cassandra instances"]?.message).isEqualTo("Instance creation failed")
        }

        @Test
        fun `should provision multiple server types in parallel`() {
            val cassandraSpec =
                InstanceSpec(
                    serverType = ServerType.Cassandra,
                    configuredCount = 3,
                    existingCount = 0,
                    instanceType = "m5.large",
                    ebsConfig = null,
                )
            val stressSpec =
                InstanceSpec(
                    serverType = ServerType.Stress,
                    configuredCount = 2,
                    existingCount = 0,
                    instanceType = "c5.large",
                    ebsConfig = null,
                )
            val config = createInstanceConfig(specs = listOf(cassandraSpec, stressSpec))

            setupMockInstanceCreation()
            setupMockInstanceCreation()

            val result = service.provisionInstances(config, emptyMap()) { _, _ -> }

            assertThat(result.errors).isEmpty()
            assertThat(result.hosts[ServerType.Cassandra]).hasSize(3)
            assertThat(result.hosts[ServerType.Stress]).hasSize(2)
        }

        @Test
        fun `should merge new hosts with existing hosts`() {
            val spec =
                InstanceSpec(
                    serverType = ServerType.Cassandra,
                    configuredCount = 3,
                    existingCount = 1,
                    instanceType = "m5.large",
                    ebsConfig = null,
                )
            val config = createInstanceConfig(specs = listOf(spec))

            val existingHosts =
                mapOf(
                    ServerType.Cassandra to listOf(createClusterHost("i-existing", "db0")),
                )

            setupMockInstanceCreation()

            val result = service.provisionInstances(config, existingHosts) { _, _ -> }

            assertThat(result.hosts[ServerType.Cassandra]).hasSize(3)
        }
    }

    @Nested
    inner class ProvisionAll {
        @Test
        fun `should create EMR cluster when spark is enabled`() {
            val specs =
                listOf(
                    InstanceSpec(
                        serverType = ServerType.Cassandra,
                        configuredCount = 1,
                        existingCount = 0,
                        instanceType = "m5.large",
                        ebsConfig = null,
                    ),
                )
            val instanceConfig = createInstanceConfig(specs = specs)
            val initConfig = createInitConfig(sparkEnabled = true)
            val servicesConfig = createServicesConfig(initConfig)

            setupMockInstanceCreation()
            setupMockEmrCreation()

            var emrCreated: EMRClusterState? = null
            val result =
                service.provisionAll(
                    instanceConfig = instanceConfig,
                    servicesConfig = servicesConfig,
                    existingHosts = emptyMap(),
                    onHostsCreated = { _, _ -> },
                    onEmrCreated = { emrCreated = it },
                    onOpenSearchCreated = { },
                )

            assertThat(result.errors).isEmpty()
            assertThat(result.emrCluster).isNotNull
            assertThat(emrCreated).isNotNull
        }

        @Test
        fun `should create OpenSearch domain when enabled`() {
            val specs =
                listOf(
                    InstanceSpec(
                        serverType = ServerType.Cassandra,
                        configuredCount = 1,
                        existingCount = 0,
                        instanceType = "m5.large",
                        ebsConfig = null,
                    ),
                )
            val instanceConfig = createInstanceConfig(specs = specs)
            val initConfig = createInitConfig(opensearchEnabled = true)
            val servicesConfig = createServicesConfig(initConfig)

            setupMockInstanceCreation()
            setupMockOpenSearchCreation()

            var openSearchCreated: OpenSearchClusterState? = null
            val result =
                service.provisionAll(
                    instanceConfig = instanceConfig,
                    servicesConfig = servicesConfig,
                    existingHosts = emptyMap(),
                    onHostsCreated = { _, _ -> },
                    onEmrCreated = { },
                    onOpenSearchCreated = { openSearchCreated = it },
                )

            assertThat(result.errors).isEmpty()
            assertThat(result.openSearchDomain).isNotNull
            assertThat(openSearchCreated).isNotNull
        }

        @Test
        fun `should collect EMR errors without affecting instances`() {
            val specs =
                listOf(
                    InstanceSpec(
                        serverType = ServerType.Cassandra,
                        configuredCount = 1,
                        existingCount = 0,
                        instanceType = "m5.large",
                        ebsConfig = null,
                    ),
                )
            val instanceConfig = createInstanceConfig(specs = specs)
            val initConfig = createInitConfig(sparkEnabled = true)
            val servicesConfig = createServicesConfig(initConfig)

            setupMockInstanceCreation()
            whenever(
                emrProvisioningService.provisionEmrCluster(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                ),
            ).thenThrow(RuntimeException("EMR failed"))

            val result =
                service.provisionAll(
                    instanceConfig = instanceConfig,
                    servicesConfig = servicesConfig,
                    existingHosts = emptyMap(),
                    onHostsCreated = { _, _ -> },
                    onEmrCreated = { },
                    onOpenSearchCreated = { },
                )

            assertThat(result.errors).containsKey("EMR cluster")
            assertThat(result.hosts[ServerType.Cassandra]).hasSize(1)
        }

        @Test
        fun `should wait for EC2 instances before starting EMR provisioning`() {
            val controlSpec =
                InstanceSpec(
                    serverType = ServerType.Control,
                    configuredCount = 1,
                    existingCount = 0,
                    instanceType = "m5.large",
                    ebsConfig = null,
                )
            val instanceConfig = createInstanceConfig(specs = listOf(controlSpec))
            val initConfig = createInitConfig(sparkEnabled = true, controlInstances = 1)
            val clusterState = createClusterState()
            val servicesConfig = createServicesConfig(initConfig, clusterState)

            setupMockInstanceCreation()
            setupMockEmrCreation()

            service.provisionAll(
                instanceConfig = instanceConfig,
                servicesConfig = servicesConfig,
                existingHosts = emptyMap(),
                onHostsCreated = { serverType, hosts ->
                    clusterState.hosts = clusterState.hosts + (serverType to hosts)
                },
                onEmrCreated = { },
                onOpenSearchCreated = { },
            )

            verify(emrProvisioningService).provisionEmrCluster(
                clusterName = eq("test-cluster"),
                masterInstanceType = any(),
                workerInstanceType = any(),
                workerCount = any(),
                subnetId = any(),
                securityGroupId = any(),
                keyName = any(),
                clusterState = argThat { hosts[ServerType.Control]?.isNotEmpty() == true },
                tags = any(),
            )
        }

        @Test
        fun `should not create EMR when spark is disabled`() {
            val specs =
                listOf(
                    InstanceSpec(
                        serverType = ServerType.Cassandra,
                        configuredCount = 1,
                        existingCount = 0,
                        instanceType = "m5.large",
                        ebsConfig = null,
                    ),
                )
            val instanceConfig = createInstanceConfig(specs = specs)
            val initConfig = createInitConfig(sparkEnabled = false)
            val servicesConfig = createServicesConfig(initConfig)

            setupMockInstanceCreation()

            val result =
                service.provisionAll(
                    instanceConfig = instanceConfig,
                    servicesConfig = servicesConfig,
                    existingHosts = emptyMap(),
                    onHostsCreated = { _, _ -> },
                    onEmrCreated = { },
                    onOpenSearchCreated = { },
                )

            verify(emrProvisioningService, never()).provisionEmrCluster(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
            assertThat(result.emrCluster).isNull()
        }
    }

    // Helper methods

    private fun setupMockInstanceCreation() {
        whenever(ec2InstanceService.createInstances(any())).thenAnswer { invocation ->
            val config = invocation.getArgument<InstanceCreationConfig>(0)
            createInstances(config.serverType, config.count)
        }
        whenever(ec2InstanceService.waitForInstancesRunning(any(), any())).thenReturn(emptyList())
        whenever(ec2InstanceService.waitForInstanceStatusOk(any(), any())).then { }
        whenever(ec2InstanceService.updateInstanceIps(any<List<CreatedInstance>>())).thenAnswer { invocation ->
            invocation.getArgument<List<CreatedInstance>>(0)
        }
    }

    private fun createInstances(
        serverType: ServerType,
        count: Int,
    ): List<CreatedInstance> =
        (0 until count).map { index ->
            CreatedInstance(
                instanceId = "i-${serverType.name.lowercase()}$index",
                publicIp = "1.1.1.$index",
                privateIp = "10.0.0.$index",
                alias = "${serverType.name.lowercase().first()}$index",
                availabilityZone = "us-west-2a",
                serverType = serverType,
            )
        }

    private fun setupMockEmrCreation() {
        val emrState =
            EMRClusterState(
                clusterId = "j-12345",
                clusterName = "test-spark",
                masterPublicDns = "ec2-1-2-3-4.compute.amazonaws.com",
                state = "RUNNING",
            )

        whenever(
            emrProvisioningService.provisionEmrCluster(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            ),
        ).thenReturn(emrState)
    }

    private fun setupMockOpenSearchCreation() {
        val createResult =
            OpenSearchDomainResult(
                domainName = "test-os",
                domainId = "123456789012/test-os",
                endpoint = null,
                dashboardsEndpoint = null,
                state = DomainState.PROCESSING,
            )
        val readyResult =
            OpenSearchDomainResult(
                domainName = "test-os",
                domainId = "123456789012/test-os",
                endpoint = "vpc-test-os-abc123.us-west-2.es.amazonaws.com",
                dashboardsEndpoint = "https://vpc-test-os-abc123.us-west-2.es.amazonaws.com/_dashboards",
                state = DomainState.ACTIVE,
            )

        whenever(openSearchService.createDomain(any())).thenReturn(createResult)
        whenever(openSearchService.waitForDomainActive(any(), any(), any())).thenReturn(readyResult)
    }

    private fun createInstanceConfig(specs: List<InstanceSpec>): InstanceProvisioningConfig =
        InstanceProvisioningConfig(
            specs = specs,
            amiId = "ami-12345",
            securityGroupId = "sg-12345",
            subnetIds = listOf("subnet-1", "subnet-2"),
            tags = mapOf("ClusterId" to "test-cluster"),
            clusterName = "test-cluster",
            userConfig = createUserConfig(),
        )

    private fun createServicesConfig(
        initConfig: InitConfig,
        clusterState: ClusterState = createClusterState(),
    ): OptionalServicesConfig =
        OptionalServicesConfig(
            initConfig = initConfig,
            subnetId = "subnet-1",
            securityGroupId = "sg-12345",
            tags = mapOf("ClusterId" to "test-cluster"),
            clusterState = clusterState,
        )

    private fun createUserConfig(): User =
        User(
            awsAccessKey = "test-access-key",
            awsSecret = "test-secret",
            region = "us-west-2",
            email = "test@example.com",
            keyName = "test-key",
            awsProfile = "",
        )

    private fun createInitConfig(
        sparkEnabled: Boolean = false,
        opensearchEnabled: Boolean = false,
        controlInstances: Int = 0,
    ): InitConfig =
        InitConfig(
            cassandraInstances = 1,
            stressInstances = 0,
            instanceType = "m5.large",
            stressInstanceType = "m5.large",
            region = "us-west-2",
            name = "test-cluster",
            ebsType = "NONE",
            ebsSize = 100,
            ebsIops = 0,
            ebsThroughput = 0,
            controlInstances = controlInstances,
            controlInstanceType = "m5.large",
            sparkEnabled = sparkEnabled,
            sparkMasterInstanceType = "m5.xlarge",
            sparkWorkerInstanceType = "m5.xlarge",
            sparkWorkerCount = 2,
            opensearchEnabled = opensearchEnabled,
            opensearchInstanceType = "t3.small.search",
            opensearchInstanceCount = 1,
            opensearchVersion = "2.11",
            opensearchEbsSize = 100,
        )

    private fun createClusterState(): ClusterState =
        ClusterState(
            clusterId = "cluster-123",
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = "test-bucket-12345",
        )

    private fun createClusterHost(
        instanceId: String,
        alias: String,
    ): ClusterHost =
        ClusterHost(
            publicIp = "1.1.1.1",
            privateIp = "10.0.0.1",
            alias = alias,
            availabilityZone = "us-west-2a",
            instanceId = instanceId,
        )
}
