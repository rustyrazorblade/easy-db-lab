package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.network.CidrBlock
import com.rustyrazorblade.easydblab.providers.aws.InstanceCreationConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The decisions `up` makes before it launches anything: each check passes when the account has
 * room, fails naming what is short and by how much when it has not, counts only the nodes that
 * will actually be launched, and makes no AWS call at all when nothing is to be launched.
 */
class LaunchCapacityPreflightTest {
    private val capacity = FakeAccountCapacity()
    private val events = mutableListOf<Event>()
    private val eventBus =
        EventBus().apply {
            addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        events += envelope.event
                    }

                    override fun close() = Unit
                },
            )
        }
    private val preflight = LaunchCapacityPreflight(capacity, eventBus)

    private fun db(
        newCount: Int,
        startIndex: Int = 0,
        instanceType: String = "i4i.xlarge",
    ) = LaunchGroup(ServerType.Cassandra, instanceType, (startIndex until startIndex + newCount).toList())

    private fun plan(
        vararg groups: LaunchGroup,
        zones: List<String> = listOf("a", "b", "c"),
        cni: CniMode = CniMode.Cilium,
        vpcId: String? = "vpc-1",
        cidr: String = "10.0.0.0/16",
    ) = CapacityPlan(
        groups = groups.toList(),
        region = "us-west-2",
        zones = zones,
        cni = cni,
        existingVpcId = vpcId,
        vpcCidr = CidrBlock(cidr),
    )

    private inline fun <reified T : Event> eventsOf(): List<T> = events.filterIsInstance<T>()

    @Test
    fun `a cluster the account can hold passes every check`() {
        preflight.verifyCapacity(plan(db(3), LaunchGroup(ServerType.Control, "m5d.xlarge", listOf(0))))

        assertThat(events.filter { it.isError() }).isEmpty()
        assertThat(eventsOf<Event.Provision.LaunchCapacityVerified>().single().newNodes).isEqualTo(4)
    }

    @Test
    fun `nothing to launch makes no AWS call and passes`() {
        preflight.verifyCapacity(plan(db(newCount = 0, startIndex = 1000), zones = (1..12).map { "z$it" }))

        assertThat(capacity.calls).isEmpty()
        assertThat(events.filter { it.isError() }).isEmpty()
    }

    @Nested
    inner class Vcpus {
        @Test
        fun `fails naming the vCPUs needed, available and short`() {
            capacity.standardQuota = 100
            capacity.standardInUse = 90

            assertThatThrownBy { preflight.verifyCapacity(plan(db(3))) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("12 vCPUs")
                .hasMessageContaining("10 available")
                .hasMessageContaining("short by 2")

            val event = eventsOf<Event.Provision.VcpuQuotaInsufficient>().single()
            assertThat(event.neededVcpus).isEqualTo(12)
            assertThat(event.quota).isEqualTo(100)
            assertThat(event.inUse).isEqualTo(90)
            assertThat(event.shortBy).isEqualTo(2)
        }

        @Test
        fun `a re-run counts only the nodes it launches`() {
            capacity.standardQuota = 4010
            capacity.standardInUse = 4000

            preflight.verifyCapacity(plan(db(newCount = 2, startIndex = 1000)))

            assertThat(eventsOf<Event.Provision.VcpuQuotaInsufficient>()).isEmpty()
        }

        @Test
        fun `a type outside the standard families is not counted against the standard quota, and says so`() {
            capacity.types += "g5.xlarge" to InstanceTypeFacts(vcpus = 4, maxNetworkInterfaces = 4, ipv4AddressesPerInterface = 15)
            capacity.standardQuota = 10
            capacity.standardInUse = 0

            preflight.verifyCapacity(plan(db(newCount = 100, instanceType = "g5.xlarge")))

            val notChecked = eventsOf<Event.Provision.VcpuQuotaNotChecked>().single()
            assertThat(notChecked.instanceType).isEqualTo("g5.xlarge")
            assertThat(notChecked.family).isEqualTo("g")
        }

        @Test
        fun `an instance type AWS does not know fails naming it`() {
            assertThatThrownBy { preflight.verifyCapacity(plan(db(1, instanceType = "zz9.huge"))) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("zz9.huge")
        }
    }

    @Nested
    inner class Zones {
        @Test
        fun `fails naming the zones where the type is not offered`() {
            capacity.offeredZones = mapOf("i4i.xlarge" to setOf("us-west-2a", "us-west-2b"))

            assertThatThrownBy { preflight.verifyCapacity(plan(db(3))) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("us-west-2c")

            val event = eventsOf<Event.Provision.InstanceTypeNotOffered>().single()
            assertThat(event.instanceType).isEqualTo("i4i.xlarge")
            assertThat(event.zones).containsExactly("us-west-2c")
        }

        @Test
        fun `only the zones the new nodes land in must offer the type`() {
            capacity.offeredZones = mapOf("i4i.xlarge" to setOf("us-west-2b", "us-west-2c"))

            // Indexes 4 and 5 land in zones b and c; db0..db3 already exist.
            preflight.verifyCapacity(plan(db(newCount = 2, startIndex = 4)))

            assertThat(eventsOf<Event.Provision.InstanceTypeNotOffered>()).isEmpty()
        }

        @Test
        fun `more zones than the VPC has subnets for fails before any AWS call`() {
            val zones = ('a'..'i').map { it.toString() }

            assertThatThrownBy { preflight.verifyCapacity(plan(db(1), zones = zones)) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("9 availability zones")
                .hasMessageContaining("${Constants.Vpc.MAX_AVAILABILITY_ZONES}")

            assertThat(capacity.calls).isEmpty()
            assertThat(eventsOf<Event.Provision.TooManyAvailabilityZones>().single().count).isEqualTo(9)
        }
    }

    @Nested
    inner class NetworkInterfaces {
        @Test
        fun `under Cilium each node needs its primary ENI and the ENIs its pod addresses fill`() {
            // i4i.xlarge: 15 addresses per ENI, one of them the ENI's own; 16 pod addresses take
            // two ENIs. Three nodes need six.
            capacity.eniQuota = 100
            capacity.enisInUse = 95

            assertThatThrownBy { preflight.verifyCapacity(plan(db(3))) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("6 network interfaces")
                .hasMessageContaining("short by 1")

            val event = eventsOf<Event.Provision.NetworkInterfaceQuotaInsufficient>().single()
            assertThat(event.needed).isEqualTo(6)
            assertThat(event.quota).isEqualTo(100)
            assertThat(event.inUse).isEqualTo(95)
            assertThat(event.shortBy).isEqualTo(1)
        }

        @Test
        fun `under Flannel each node needs only its primary ENI`() {
            capacity.eniQuota = 100
            capacity.enisInUse = 95

            preflight.verifyCapacity(plan(db(3), cni = CniMode.Flannel))

            assertThat(eventsOf<Event.Provision.NetworkInterfaceQuotaInsufficient>()).isEmpty()
        }

        @Test
        fun `the ENIs a node may need are capped at what its type can attach`() {
            capacity.types += "t3.nano" to InstanceTypeFacts(vcpus = 2, maxNetworkInterfaces = 2, ipv4AddressesPerInterface = 2)
            capacity.eniQuota = 2
            capacity.enisInUse = 0

            preflight.verifyCapacity(plan(db(1, instanceType = "t3.nano")))

            assertThat(eventsOf<Event.Provision.NetworkInterfaceQuotaInsufficient>()).isEmpty()
        }
    }

    @Nested
    inner class SubnetAddresses {
        @Test
        fun `fails naming the zone whose subnet has too few free addresses`() {
            // Six nodes over three zones: two per zone, each a node address plus 16 pod addresses.
            capacity.freeAddresses = mapOf("us-west-2a" to 20, "us-west-2b" to 1000, "us-west-2c" to 1000)

            assertThatThrownBy { preflight.verifyCapacity(plan(db(6))) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("us-west-2a")
                .hasMessageContaining("short by 14")

            val event = eventsOf<Event.Provision.SubnetAddressesInsufficient>().single()
            assertThat(event.zone).isEqualTo("us-west-2a")
            assertThat(event.needed).isEqualTo(34)
            assertThat(event.available).isEqualTo(20)
        }

        @Test
        fun `every group's new nodes count toward the zone they land in`() {
            capacity.freeAddresses = mapOf("us-west-2a" to 34, "us-west-2b" to 1000, "us-west-2c" to 1000)

            // db0, db3 and app0 land in zone a: 3 x 17 = 51.
            assertThatThrownBy {
                preflight.verifyCapacity(plan(db(4), LaunchGroup(ServerType.Stress, "c6id.2xlarge", listOf(0))))
            }.hasMessageContaining("us-west-2a")
        }

        @Test
        fun `with no VPC yet the subnet size comes from the CIDR, and no subnet is looked up`() {
            // A /25 splits into eight /28s: 16 addresses, 5 reserved by AWS, 11 free.
            assertThatThrownBy { preflight.verifyCapacity(plan(db(1), vpcId = null, cidr = "10.0.0.0/25")) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("short by 6")

            assertThat(capacity.calls).doesNotContain("freeAddressesByZone")
            assertThat(eventsOf<Event.Provision.SubnetAddressesInsufficient>().single().available).isEqualTo(11)
        }

        @Test
        fun `a zone the VPC has no subnet in yet is sized from the CIDR`() {
            capacity.freeAddresses = mapOf("us-west-2a" to 1000)

            preflight.verifyCapacity(plan(db(3), cidr = "10.0.0.0/16"))

            assertThat(eventsOf<Event.Provision.SubnetAddressesInsufficient>()).isEmpty()
        }

        @Test
        fun `1,000 db nodes over three zones fit the default VPC under Cilium`() {
            preflight.verifyCapacity(plan(db(1000), vpcId = null, cidr = Constants.Vpc.DEFAULT_CIDR))

            assertThat(events.filter { it.isError() }).isEmpty()
        }
    }

    @Test
    fun `every shortfall is reported, not only the first`() {
        capacity.standardQuota = 0
        capacity.eniQuota = 0

        assertThatThrownBy { preflight.verifyCapacity(plan(db(1))) }
            .hasMessageContaining("vCPU")
            .hasMessageContaining("network interfaces")

        assertThat(eventsOf<Event.Provision.VcpuQuotaInsufficient>()).hasSize(1)
        assertThat(eventsOf<Event.Provision.NetworkInterfaceQuotaInsufficient>()).hasSize(1)
    }

    @Nested
    inner class DryRun {
        private fun launch(
            serverType: ServerType,
            count: Int,
        ) = InstanceCreationConfig(
            serverType = serverType,
            indexes = (0 until count).toList(),
            instanceType = "i4i.xlarge",
            amiId = "ami-real",
            keyName = "key",
            securityGroupId = "sg-1",
            subnetIds = listOf("subnet-a"),
            iamInstanceProfile = "profile",
            ebsConfig = null,
            tags = emptyMap(),
            clusterName = "lab",
        )

        @Test
        fun `a launch AWS permits passes, and each group is asked about with its own request`() {
            preflight.verifyLaunchPermitted(listOf(launch(ServerType.Cassandra, 3), launch(ServerType.Control, 1)))

            assertThat(capacity.dryRuns.map { it.serverType }).containsExactly(ServerType.Cassandra, ServerType.Control)
            assertThat(capacity.dryRuns).allMatch { it.amiId == "ami-real" && it.securityGroupId == "sg-1" }
        }

        @Test
        fun `a launch AWS refuses fails with AWS's message`() {
            capacity.dryRun = { config ->
                if (config.serverType == ServerType.Cassandra) {
                    LaunchDryRun.Refused("UnauthorizedOperation", "You are not authorized to perform this operation.")
                } else {
                    LaunchDryRun.Permitted
                }
            }

            assertThatThrownBy {
                preflight.verifyLaunchPermitted(listOf(launch(ServerType.Cassandra, 3), launch(ServerType.Control, 1)))
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("UnauthorizedOperation")
                .hasMessageContaining("You are not authorized to perform this operation.")

            val refused = eventsOf<Event.Provision.LaunchDryRunRefused>().single()
            assertThat(refused.serverType).isEqualTo("db")
            assertThat(refused.errorCode).isEqualTo("UnauthorizedOperation")
        }

        @Test
        fun `no group to launch makes no dry run`() {
            preflight.verifyLaunchPermitted(emptyList())

            assertThat(capacity.calls).isEmpty()
        }
    }
}
