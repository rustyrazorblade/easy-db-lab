package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping
import software.amazon.awssdk.services.ec2.model.EbsBlockDevice
import software.amazon.awssdk.services.ec2.model.RegisterImageRequest

/**
 * Integration tests for AMIService low-level EC2 operations using LocalStack.
 *
 * Tests verify that AWS SDK request construction, filter handling, and response
 * parsing work correctly against a real EC2-compatible API.
 *
 * Note: LocalStack Community Edition does not faithfully implement all AMI fields
 * (e.g., architecture returns null). Architecture normalization is covered in unit tests.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AMIServiceIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val localStack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                .withServices(Service.EC2)
    }

    private lateinit var ec2Client: Ec2Client
    private lateinit var outputHandler: BufferedOutputHandler
    private lateinit var amiService: AMIService

    @BeforeAll
    fun setupClients() {
        val credentials =
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    localStack.accessKey,
                    localStack.secretKey,
                ),
            )

        ec2Client =
            Ec2Client
                .builder()
                .endpointOverride(localStack.getEndpointOverride(Service.EC2))
                .region(Region.of(localStack.region))
                .credentialsProvider(credentials)
                .build()
    }

    @BeforeEach
    fun setup() {
        outputHandler = BufferedOutputHandler()
        val aws: AWS = mock()
        amiService = AMIService(ec2Client, outputHandler, aws)
    }

    /**
     * Registers a test AMI in LocalStack and returns the AMI ID.
     */
    private fun registerTestAMI(name: String): String {
        val request =
            RegisterImageRequest
                .builder()
                .name(name)
                .rootDeviceName("/dev/sda1")
                .blockDeviceMappings(
                    BlockDeviceMapping
                        .builder()
                        .deviceName("/dev/sda1")
                        .ebs(
                            EbsBlockDevice
                                .builder()
                                .snapshotId("snap-test123")
                                .volumeSize(8)
                                .build(),
                        ).build(),
                ).build()

        return ec2Client.registerImage(request).imageId()
    }

    @Nested
    inner class ListPrivateAMIs {
        @Test
        fun `should list AMIs matching name pattern`() {
            val amiId = registerTestAMI("rustyrazorblade/images/easy-db-lab-cassandra-amd64-20240101")

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
            registerTestAMI("rustyrazorblade/images/easy-db-lab-cassandra-amd64-20240201")
            registerTestAMI("rustyrazorblade/images/easy-db-lab-base-amd64-20240201")

            val cassandraResult =
                amiService.listPrivateAMIs("rustyrazorblade/images/easy-db-lab-cassandra-*")

            assertThat(cassandraResult).allMatch {
                it.name.contains("cassandra")
            }
        }

        @Test
        fun `should return AMI with correct fields populated`() {
            val amiId = registerTestAMI("test-fields-populated")

            val result = amiService.listPrivateAMIs("test-fields-populated")

            assertThat(result).hasSize(1)
            val ami = result.first()
            assertThat(ami.id).isEqualTo(amiId)
            assertThat(ami.name).isEqualTo("test-fields-populated")
            assertThat(ami.creationDate).isNotNull()
            assertThat(ami.ownerId).isNotBlank()
        }

        @Test
        fun `should return multiple AMIs matching wildcard pattern`() {
            registerTestAMI("multi-match-test-amd64-20240101")
            registerTestAMI("multi-match-test-amd64-20240202")

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
            val amiId = registerTestAMI("test-deregister-ami")

            // Should not throw
            amiService.deregisterAMI(amiId)

            // After deregistering, listing should not return it
            val result = amiService.listPrivateAMIs("test-deregister-ami")
            assertThat(result).isEmpty()
        }
    }
}
