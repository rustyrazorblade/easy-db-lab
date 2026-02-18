package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.providers.aws.model.AMI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * Tests for AMIResolver.
 */
class AMIResolverTest {
    private lateinit var amiService: AMIService
    private lateinit var resolver: AMIResolver

    @BeforeEach
    fun setup() {
        amiService = mock()
        resolver = DefaultAMIResolver(amiService)
    }

    @Nested
    inner class ResolveAmiId {
        @Test
        fun `should return explicit AMI ID when provided`() {
            val explicitAmiId = "ami-explicit123"

            val result = resolver.resolveAmiId(explicitAmiId, "amd64")

            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()).isEqualTo(explicitAmiId)
            verify(amiService, never()).listPrivateAMIs(any(), any())
        }

        @Test
        fun `should auto-resolve AMI when explicit ID is blank`() {
            val expectedAmi = createAmi("ami-auto123", "2024-01-15T10:00:00Z")
            whenever(amiService.listPrivateAMIs(any(), any())).thenReturn(listOf(expectedAmi))

            val result = resolver.resolveAmiId("", "amd64")

            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()).isEqualTo("ami-auto123")
        }

        @Test
        fun `should select most recent AMI when multiple exist`() {
            val olderAmi = createAmi("ami-older", "2024-01-01T10:00:00Z")
            val newerAmi = createAmi("ami-newer", "2024-01-15T10:00:00Z")
            val oldestAmi = createAmi("ami-oldest", "2023-12-01T10:00:00Z")
            whenever(amiService.listPrivateAMIs(any(), any()))
                .thenReturn(listOf(olderAmi, newerAmi, oldestAmi))

            val result = resolver.resolveAmiId("", "amd64")

            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()).isEqualTo("ami-newer")
        }

        @Test
        fun `should return failure when no AMIs found`() {
            whenever(amiService.listPrivateAMIs(any(), any())).thenReturn(emptyList())

            val result = resolver.resolveAmiId("", "arm64")

            assertThat(result.isFailure).isTrue()
            val exception = result.exceptionOrNull()
            assertThat(exception).isInstanceOf(NoAmiFoundException::class.java)
            assertThat(exception?.message).contains("No AMI found for architecture arm64")
            assertThat(exception?.message).contains("easy-db-lab build-image")
        }

        @Test
        fun `should lowercase architecture when auto-resolving`() {
            val ami = createAmi("ami-123", "2024-01-15T10:00:00Z")
            whenever(amiService.listPrivateAMIs(any(), any())).thenReturn(listOf(ami))

            resolver.resolveAmiId("", "AMD64")

            val expectedPattern = Constants.AWS.AMI_PATTERN_TEMPLATE.format("amd64")
            verify(amiService).listPrivateAMIs(expectedPattern, "self")
        }
    }

    @Nested
    inner class GenerateAmiPattern {
        @Test
        fun `should generate pattern for amd64`() {
            val pattern = resolver.generateAmiPattern("amd64")

            assertThat(pattern).isEqualTo("rustyrazorblade/images/easy-db-lab-cassandra-amd64-*")
        }

        @Test
        fun `should generate pattern for arm64`() {
            val pattern = resolver.generateAmiPattern("arm64")

            assertThat(pattern).isEqualTo("rustyrazorblade/images/easy-db-lab-cassandra-arm64-*")
        }

        @Test
        fun `should lowercase architecture in pattern`() {
            val pattern = resolver.generateAmiPattern("ARM64")

            assertThat(pattern).isEqualTo("rustyrazorblade/images/easy-db-lab-cassandra-arm64-*")
        }
    }

    @Nested
    inner class FindAmisForArchitecture {
        @Test
        fun `should call AMIService with correct pattern`() {
            whenever(amiService.listPrivateAMIs(any(), any())).thenReturn(emptyList())

            resolver.findAmisForArchitecture("amd64")

            val expectedPattern = "rustyrazorblade/images/easy-db-lab-cassandra-amd64-*"
            verify(amiService).listPrivateAMIs(expectedPattern, "self")
        }

        @Test
        fun `should return AMIs from AMIService`() {
            val ami1 = createAmi("ami-1", "2024-01-01T10:00:00Z")
            val ami2 = createAmi("ami-2", "2024-01-02T10:00:00Z")
            whenever(amiService.listPrivateAMIs(any(), any())).thenReturn(listOf(ami1, ami2))

            val result = resolver.findAmisForArchitecture("amd64")

            assertThat(result).hasSize(2)
            assertThat(result.map { it.id }).containsExactly("ami-1", "ami-2")
        }
    }

    @Nested
    inner class SelectMostRecentAmi {
        @Test
        fun `should return null for empty list`() {
            val result = resolver.selectMostRecentAmi(emptyList())

            assertThat(result).isNull()
        }

        @Test
        fun `should return single AMI from list of one`() {
            val ami = createAmi("ami-single", "2024-01-15T10:00:00Z")

            val result = resolver.selectMostRecentAmi(listOf(ami))

            assertThat(result).isEqualTo(ami)
        }

        @Test
        fun `should return most recent AMI from multiple`() {
            val oldest = createAmi("ami-oldest", "2023-01-01T10:00:00Z")
            val middle = createAmi("ami-middle", "2024-01-15T10:00:00Z")
            val newest = createAmi("ami-newest", "2024-06-01T10:00:00Z")

            val result = resolver.selectMostRecentAmi(listOf(oldest, newest, middle))

            assertThat(result).isEqualTo(newest)
        }
    }

    /**
     * Helper to create an AMI for testing.
     */
    private fun createAmi(
        id: String,
        creationDate: String,
        architecture: String = "amd64",
    ): AMI =
        AMI(
            id = id,
            name = "test-ami-$id",
            architecture = architecture,
            creationDate = Instant.parse(creationDate),
            ownerId = "123456789012",
            isPublic = false,
            snapshotIds = emptyList(),
        )
}
