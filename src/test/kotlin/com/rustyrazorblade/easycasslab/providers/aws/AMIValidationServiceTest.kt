package com.rustyrazorblade.easycasslab.providers.aws

import com.rustyrazorblade.easycasslab.BaseKoinTest
import com.rustyrazorblade.easycasslab.configuration.Arch
import com.rustyrazorblade.easycasslab.output.OutputHandler
import com.rustyrazorblade.easycasslab.providers.aws.model.AMI
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import java.time.Instant

class AMIValidationServiceTest : BaseKoinTest() {
    private lateinit var mockEC2Service: EC2Service
    private lateinit var mockOutputHandler: OutputHandler
    private lateinit var mockAWS: AWS
    private lateinit var validationService: AMIValidationService

    companion object {
        private const val TEST_ACCOUNT_ID = "123456789012"
    }

    @BeforeEach
    fun setup() {
        mockEC2Service = mock()
        mockOutputHandler = mock()
        mockAWS = mock()

        // Mock the account ID retrieval
        whenever(mockAWS.getAccountId()).thenReturn(TEST_ACCOUNT_ID)

        validationService = AMIValidationService(mockEC2Service, mockOutputHandler, mockAWS)
    }

    // Happy Path Tests

    @Test
    fun `should validate explicit AMI with matching architecture`() {
        val testAMI =
            createTestAMI(
                id = "ami-123",
                architecture = "amd64",
            )

        whenever(mockEC2Service.listPrivateAMIs("ami-123", TEST_ACCOUNT_ID))
            .thenReturn(listOf(testAMI))

        val result =
            validationService.validateAMI(
                overrideAMI = "ami-123",
                requiredArchitecture = Arch.AMD64,
            )

        assertThat(result).isEqualTo(testAMI)
        verify(mockEC2Service).listPrivateAMIs("ami-123", TEST_ACCOUNT_ID)
    }

    @Test
    fun `should find AMI by pattern with matching architecture`() {
        val testAMI =
            createTestAMI(
                id = "ami-456",
                architecture = "amd64",
                name = "rustyrazorblade/images/easy-cass-lab-cassandra-amd64-20240101",
            )

        whenever(mockEC2Service.listPrivateAMIs(any(), any()))
            .thenReturn(listOf(testAMI))

        val result =
            validationService.validateAMI(
                overrideAMI = "",
                requiredArchitecture = Arch.AMD64,
            )

        assertThat(result).isEqualTo(testAMI)
    }

    @Test
    fun `should select newest AMI when multiple match`() {
        val older =
            createTestAMI(
                id = "ami-old",
                creationDate = Instant.parse("2024-01-01T00:00:00Z"),
            )
        val newer =
            createTestAMI(
                id = "ami-new",
                creationDate = Instant.parse("2024-12-01T00:00:00Z"),
            )

        whenever(mockEC2Service.listPrivateAMIs(any(), any()))
            .thenReturn(listOf(older, newer))

        val result =
            validationService.validateAMI(
                overrideAMI = "",
                requiredArchitecture = Arch.AMD64,
            )

        assertThat(result.id).isEqualTo("ami-new")
        verify(mockOutputHandler).handleMessage(any())
    }

    // Error Cases

    @Test
    fun `should throw when explicit AMI architecture mismatches`() {
        val testAMI =
            createTestAMI(
                id = "ami-123",
                architecture = "arm64",
            )

        whenever(mockEC2Service.listPrivateAMIs("ami-123", TEST_ACCOUNT_ID))
            .thenReturn(listOf(testAMI))

        assertThatThrownBy {
            validationService.validateAMI(
                overrideAMI = "ami-123",
                requiredArchitecture = Arch.AMD64,
            )
        }.isInstanceOf(AMIValidationException.ArchitectureMismatch::class.java)
            .hasMessageContaining("arm64")
            .hasMessageContaining("amd64")
    }

    @Test
    fun `should throw when no AMI found by pattern`() {
        whenever(mockEC2Service.listPrivateAMIs(any(), any()))
            .thenReturn(emptyList())

        assertThatThrownBy {
            validationService.validateAMI(
                overrideAMI = "",
                requiredArchitecture = Arch.AMD64,
            )
        }.isInstanceOf(AMIValidationException.NoAMIFound::class.java)

        verify(mockOutputHandler).handleMessage(any())
    }

    @Test
    fun `should throw when no AMI matches required architecture`() {
        val wrongArchAMI = createTestAMI(architecture = "arm64")

        whenever(mockEC2Service.listPrivateAMIs(any(), any()))
            .thenReturn(listOf(wrongArchAMI))

        assertThatThrownBy {
            validationService.validateAMI(
                overrideAMI = "",
                requiredArchitecture = Arch.AMD64,
            )
        }.isInstanceOf(AMIValidationException.ArchitectureMismatch::class.java)
    }

    @Test
    fun `should throw when explicit AMI not found`() {
        whenever(mockEC2Service.listPrivateAMIs("ami-nonexistent", TEST_ACCOUNT_ID))
            .thenReturn(emptyList())

        assertThatThrownBy {
            validationService.validateAMI(
                overrideAMI = "ami-nonexistent",
                requiredArchitecture = Arch.AMD64,
            )
        }.isInstanceOf(AMIValidationException.NoAMIFound::class.java)
            .hasMessageContaining("ami-nonexistent")
    }

    // Retry Logic Tests

    @Test
    fun `should retry on transient AWS errors`() {
        val testAMI = createTestAMI()
        val ec2Exception =
            Ec2Exception
                .builder()
                .statusCode(503)
                .message("Service unavailable")
                .build()

        whenever(mockEC2Service.listPrivateAMIs(any(), any()))
            .thenThrow(ec2Exception)
            .thenThrow(ec2Exception)
            .thenReturn(listOf(testAMI))

        val result =
            validationService.validateAMI(
                overrideAMI = "",
                requiredArchitecture = Arch.AMD64,
            )

        assertThat(result).isEqualTo(testAMI)
        verify(mockEC2Service, times(3)).listPrivateAMIs(any(), any())
    }

    @Test
    fun `should NOT retry on permission errors`() {
        val ec2Exception =
            Ec2Exception
                .builder()
                .statusCode(403)
                .message("Access denied")
                .build()

        whenever(mockEC2Service.listPrivateAMIs(any(), any()))
            .thenThrow(ec2Exception)

        assertThatThrownBy {
            validationService.validateAMI(
                overrideAMI = "",
                requiredArchitecture = Arch.AMD64,
            )
        }.isInstanceOf(AMIValidationException.AWSServiceError::class.java)

        // Should only attempt once (no retries)
        verify(mockEC2Service, times(1)).listPrivateAMIs(any(), any())
    }

    @Test
    fun `should retry on rate limiting (429)`() {
        val testAMI = createTestAMI()
        val ec2Exception =
            Ec2Exception
                .builder()
                .statusCode(429)
                .message("Too many requests")
                .build()

        whenever(mockEC2Service.listPrivateAMIs(any(), any()))
            .thenThrow(ec2Exception)
            .thenReturn(listOf(testAMI))

        val result =
            validationService.validateAMI(
                overrideAMI = "",
                requiredArchitecture = Arch.AMD64,
            )

        assertThat(result).isEqualTo(testAMI)
        verify(mockEC2Service, times(2)).listPrivateAMIs(any(), any())
    }

    // Pattern Configuration Tests

    @Test
    fun `should use custom pattern when provided`() {
        val testAMI = createTestAMI()
        val customPattern = "custom-ami-pattern-*"

        whenever(mockEC2Service.listPrivateAMIs(customPattern, TEST_ACCOUNT_ID))
            .thenReturn(listOf(testAMI))

        validationService.validateAMI(
            overrideAMI = "",
            requiredArchitecture = Arch.AMD64,
            amiPattern = customPattern,
        )

        verify(mockEC2Service).listPrivateAMIs(customPattern, TEST_ACCOUNT_ID)
    }

    @Test
    fun `should inject architecture into default pattern for AMD64`() {
        val testAMI = createTestAMI(architecture = "amd64")

        whenever(
            mockEC2Service.listPrivateAMIs(
                "rustyrazorblade/images/easy-cass-lab-cassandra-amd64-*",
                TEST_ACCOUNT_ID,
            ),
        ).thenReturn(listOf(testAMI))

        validationService.validateAMI(
            overrideAMI = "",
            requiredArchitecture = Arch.AMD64,
        )

        verify(mockEC2Service).listPrivateAMIs(
            "rustyrazorblade/images/easy-cass-lab-cassandra-amd64-*",
            TEST_ACCOUNT_ID,
        )
    }

    @Test
    fun `should inject architecture into default pattern for ARM64`() {
        val testAMI = createTestAMI(architecture = "arm64")

        whenever(
            mockEC2Service.listPrivateAMIs(
                "rustyrazorblade/images/easy-cass-lab-cassandra-arm64-*",
                TEST_ACCOUNT_ID,
            ),
        ).thenReturn(listOf(testAMI))

        validationService.validateAMI(
            overrideAMI = "",
            requiredArchitecture = Arch.ARM64,
        )

        verify(mockEC2Service).listPrivateAMIs(
            "rustyrazorblade/images/easy-cass-lab-cassandra-arm64-*",
            TEST_ACCOUNT_ID,
        )
    }

    // Helper method
    private fun createTestAMI(
        id: String = "ami-test",
        name: String = "rustyrazorblade/images/easy-cass-lab-cassandra-amd64-20240101",
        architecture: String = "amd64",
        creationDate: Instant = Instant.now(),
    ) = AMI(
        id = id,
        name = name,
        architecture = architecture,
        creationDate = creationDate,
        ownerId = "123456789012",
        isPublic = false,
        snapshotIds = emptyList(),
    )
}
