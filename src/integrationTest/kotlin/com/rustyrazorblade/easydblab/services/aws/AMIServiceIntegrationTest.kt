package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.AWS
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.CreateImageRequest
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest
import software.amazon.awssdk.services.ec2.model.Filter
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest

/**
 * Integration tests for AMIService low-level EC2 operations using LocalStack.
 *
 * Tests verify that AWS SDK request construction, filter handling, and response
 * parsing work correctly against a real EC2-compatible API.
 *
 * Uses the JVM-wide [SharedLocalStack] EC2 backend, shared with the other EC2
 * integration tests. AMI names registered here are unique to this class, and
 * every assertion filters by this class's own name patterns, so co-tenant EC2
 * resources (e.g. VPCs from other test classes) cannot affect these results.
 *
 * Every test image is made with CreateImage from one x86_64 instance, as a Packer bake makes one,
 * so it has an architecture, as every real AMI has. LocalStack 4 drops the architecture of an
 * image made with RegisterImage. The test checks that `x86_64` is listed as `amd64`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AMIServiceIntegrationTest {
    private lateinit var ec2Client: Ec2Client
    private lateinit var eventBus: EventBus
    private lateinit var amiService: AMIService
    private lateinit var instanceId: String

    @BeforeAll
    fun setupClients() {
        ec2Client = SharedLocalStack.ec2Client()
        val stockImage =
            ec2Client
                .describeImages(
                    DescribeImagesRequest
                        .builder()
                        .owners("amazon")
                        .filters(
                            Filter
                                .builder()
                                .name("architecture")
                                .values("x86_64")
                                .build(),
                        ).build(),
                ).images()
                .first()
                .imageId()
        instanceId =
            ec2Client
                .runInstances(
                    RunInstancesRequest
                        .builder()
                        .imageId(stockImage)
                        .minCount(1)
                        .maxCount(1)
                        .build(),
                ).instances()
                .single()
                .instanceId()
    }

    @BeforeEach
    fun setup() {
        eventBus = EventBus()
        val aws: AWS = mock()
        amiService = AMIService(ec2Client, eventBus, aws)
    }

    /** Creates a test AMI named [name] from the test's x86_64 instance and returns its ID. */
    private fun createTestAMI(name: String): String =
        ec2Client
            .createImage(
                CreateImageRequest
                    .builder()
                    .instanceId(instanceId)
                    .name(name)
                    .build(),
            ).imageId()

    @Nested
    inner class ListPrivateAMIs {
        @Test
        fun `should list AMIs matching name pattern`() {
            val amiId = createTestAMI("rustyrazorblade/images/easy-db-lab-cassandra-amd64-20240101")

            val result = amiService.listPrivateAMIs("rustyrazorblade/images/easy-db-lab-cassandra-amd64-*")

            assertThat(result).anyMatch { it.id == amiId }
            val matchedAmi = result.first { it.id == amiId }
            assertThat(matchedAmi.name)
                .isEqualTo("rustyrazorblade/images/easy-db-lab-cassandra-amd64-20240101")
        }

        @Test
        fun `should return empty list when no AMIs match pattern`() {
            val result = amiService.listPrivateAMIs("nonexistent-pattern-*")

            assertThat(result).isEmpty()
        }

        @Test
        fun `should filter by name pattern and not return unmatched AMIs`() {
            createTestAMI("rustyrazorblade/images/easy-db-lab-cassandra-amd64-20240201")
            createTestAMI("rustyrazorblade/images/easy-db-lab-base-amd64-20240201")

            val cassandraResult =
                amiService.listPrivateAMIs("rustyrazorblade/images/easy-db-lab-cassandra-*")

            assertThat(cassandraResult).allMatch {
                it.name.contains("cassandra")
            }
        }

        @Test
        fun `should return AMI with correct fields populated`() {
            val amiId = createTestAMI("test-fields-populated")

            val result = amiService.listPrivateAMIs("test-fields-populated")

            assertThat(result).hasSize(1)
            val ami = result.first()
            assertThat(ami.id).isEqualTo(amiId)
            assertThat(ami.name).isEqualTo("test-fields-populated")
            assertThat(ami.architecture).isEqualTo("amd64")
            assertThat(ami.creationDate).isNotNull()
            assertThat(ami.ownerId).isNotBlank()
        }

        @Test
        fun `should return multiple AMIs matching wildcard pattern`() {
            createTestAMI("multi-match-test-amd64-20240101")
            createTestAMI("multi-match-test-amd64-20240202")

            val result = amiService.listPrivateAMIs("multi-match-test-*")

            assertThat(result).hasSizeGreaterThanOrEqualTo(2)
            assertThat(result.map { it.name }).contains(
                "multi-match-test-amd64-20240101",
                "multi-match-test-amd64-20240202",
            )
        }
    }

    @Nested
    inner class DeregisterAMI {
        @Test
        fun `should deregister an AMI without error`() {
            val amiId = createTestAMI("test-deregister-ami")

            // Should not throw
            amiService.deregisterAMI(amiId)

            // After deregistering, listing should not return it
            val result = amiService.listPrivateAMIs("test-deregister-ami")
            assertThat(result).isEmpty()
        }
    }
}
