package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.providers.aws.model.AMI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.ec2.Ec2Client
import java.time.Instant
import com.rustyrazorblade.easydblab.assertions.assertThat as assertThatPruneResult

/**
 * Unit tests for AMIService pruning operations.
 *
 * Tests cover:
 * - Basic pruning behavior by architecture and type
 * - Dry-run mode
 * - Snapshot deletion
 * - Type filtering
 * - Edge cases (empty lists, malformed data)
 * - Sorting behavior of results
 */
internal class AMIServiceTest {
    private val mockEc2Client: Ec2Client = mock()
    private val mockOutputHandler: OutputHandler = mock()
    private val mockAws: AWS = mock()
    private lateinit var amiService: AMIService

    @BeforeEach
    fun setup() {
        amiService =
            spy(
                AMIService(
                    ec2Client = mockEc2Client,
                    outputHandler = mockOutputHandler,
                    aws = mockAws,
                ),
            )
    }

    @Test
    fun `pruneAMIs should keep newest N AMIs per architecture and type`() {
        val amis =
            listOf(
                cassandraAMI("ami-1", "amd64", "20240103"),
                cassandraAMI("ami-2", "amd64", "20240102"),
                cassandraAMI("ami-3", "amd64", "20240101"),
                cassandraAMI("ami-4", "arm64", "20240103"),
                cassandraAMI("ami-5", "arm64", "20240102"),
            )

        doReturn(amis).whenever(amiService).listPrivateAMIs(any(), any())

        val result = amiService.pruneAMIs(namePattern = NAME_PATTERN, keepCount = 2, dryRun = false)

        assertThatPruneResult(result)
            .hasDeletedCount(1)
            .hasKeptCount(4)
            .deletedAMIsWithIds("ami-3")
            .keptAMIsWithIds("ami-1", "ami-2", "ami-4", "ami-5")

        verify(amiService).deregisterAMI("ami-3")
    }

    @Test
    fun `pruneAMIs should handle multiple types independently`() {
        val amis =
            listOf(
                cassandraAMI("ami-1", "amd64", "20240103"),
                cassandraAMI("ami-2", "amd64", "20240102"),
                baseAMI("ami-3", "amd64", "20240103"),
                baseAMI("ami-4", "amd64", "20240102"),
            )

        doReturn(amis).whenever(amiService).listPrivateAMIs(any(), any())

        val result = amiService.pruneAMIs(namePattern = NAME_PATTERN, keepCount = 1, dryRun = false)

        assertThatPruneResult(result)
            .hasDeletedCount(2)
            .hasKeptCount(2)
            .deletedAMIsWithIds("ami-2", "ami-4")
            .keptAMIsWithIds("ami-1", "ami-3")

        verify(amiService).deregisterAMI("ami-2")
        verify(amiService).deregisterAMI("ami-4")
    }

    @Test
    fun `pruneAMIs in dry-run mode should not delete AMIs`() {
        val amis =
            listOf(
                cassandraAMI("ami-1", "amd64", "20240103"),
                cassandraAMI("ami-2", "amd64", "20240102"),
                cassandraAMI("ami-3", "amd64", "20240101"),
            )

        doReturn(amis).whenever(amiService).listPrivateAMIs(any(), any())

        val result = amiService.pruneAMIs(namePattern = NAME_PATTERN, keepCount = 2, dryRun = true)

        assertThatPruneResult(result)
            .hasDeletedCount(1)
            .hasKeptCount(2)
            .deletedAMIsWithIds("ami-3")

        verify(amiService, never()).deregisterAMI(any())
        verify(amiService, never()).deleteSnapshot(any())
    }

    @Test
    fun `pruneAMIs should delete associated snapshots`() {
        val amis =
            listOf(
                cassandraAMI("ami-1", "amd64", "20240103", listOf("snap-1")),
                cassandraAMI("ami-2", "amd64", "20240102", listOf("snap-2", "snap-3")),
            )

        doReturn(amis).whenever(amiService).listPrivateAMIs(any(), any())

        val result = amiService.pruneAMIs(namePattern = NAME_PATTERN, keepCount = 1, dryRun = false)

        assertThatPruneResult(result)
            .hasDeletedCount(1)
            .deletedAMIsWithIds("ami-2")

        verify(amiService).deregisterAMI("ami-2")
        verify(amiService).deleteSnapshot("snap-2")
        verify(amiService).deleteSnapshot("snap-3")
        verify(amiService, never()).deleteSnapshot("snap-1")
    }

    @Test
    fun `pruneAMIs should filter by AMI type when specified`() {
        val amis =
            listOf(
                cassandraAMI("ami-1", "amd64", "20240103"),
                cassandraAMI("ami-2", "amd64", "20240102"),
                baseAMI("ami-3", "amd64", "20240103"),
                baseAMI("ami-4", "amd64", "20240102"),
            )

        doReturn(amis).whenever(amiService).listPrivateAMIs(any(), any())

        val result =
            amiService.pruneAMIs(
                namePattern = NAME_PATTERN,
                keepCount = 1,
                dryRun = false,
                typeFilter = "cassandra",
            )

        assertThatPruneResult(result)
            .hasDeletedCount(1)
            .hasKeptCount(1)
            .deletedAMIsWithIds("ami-2")
            .keptAMIsWithIds("ami-1")

        verify(amiService).deregisterAMI("ami-2")
        verify(amiService, never()).deregisterAMI("ami-3")
        verify(amiService, never()).deregisterAMI("ami-4")
    }

    @Test
    fun `pruneAMIs should keep all AMIs when keepCount is greater than or equal to available`() {
        val amis =
            listOf(
                cassandraAMI("ami-1", "amd64", "20240103"),
                cassandraAMI("ami-2", "amd64", "20240102"),
            )

        doReturn(amis).whenever(amiService).listPrivateAMIs(any(), any())

        val result = amiService.pruneAMIs(namePattern = NAME_PATTERN, keepCount = 5, dryRun = false)

        assertThatPruneResult(result)
            .hasNoDeleted()
            .hasKeptCount(2)
            .keptAMIsWithIds("ami-1", "ami-2")

        verify(amiService, never()).deregisterAMI(any())
    }

    @Test
    fun `pruneAMIs should handle empty AMI list`() {
        doReturn(emptyList<AMI>()).whenever(amiService).listPrivateAMIs(any(), any())

        val result = amiService.pruneAMIs(namePattern = NAME_PATTERN, keepCount = 2, dryRun = false)

        assertThatPruneResult(result)
            .hasNoDeleted()
            .hasNoKept()

        verify(amiService, never()).deregisterAMI(any())
    }

    @Test
    fun `pruneAMIs should handle AMIs without snapshots`() {
        val amis =
            listOf(
                cassandraAMI("ami-1", "amd64", "20240103"),
                cassandraAMI("ami-2", "amd64", "20240102"),
            )

        doReturn(amis).whenever(amiService).listPrivateAMIs(any(), any())

        val result = amiService.pruneAMIs(namePattern = NAME_PATTERN, keepCount = 1, dryRun = false)

        assertThatPruneResult(result)
            .hasDeletedCount(1)
            .deletedAMIsWithIds("ami-2")

        verify(amiService).deregisterAMI("ami-2")
        verify(amiService, never()).deleteSnapshot(any())
    }

    @Test
    fun `pruneAMIs should respect type filter case-insensitively`() {
        val amis =
            listOf(
                cassandraAMI("ami-1", "amd64", "20240103"),
                cassandraAMI("ami-2", "amd64", "20240102"),
                baseAMI("ami-3", "amd64", "20240103"),
            )

        doReturn(amis).whenever(amiService).listPrivateAMIs(any(), any())

        val result =
            amiService.pruneAMIs(
                namePattern = NAME_PATTERN,
                keepCount = 1,
                dryRun = false,
                typeFilter = "CASSANDRA",
            )

        assertThatPruneResult(result)
            .hasDeletedCount(1)
            .deletedAMIsWithIds("ami-2")

        verify(amiService).deregisterAMI("ami-2")
        verify(amiService, never()).deregisterAMI("ami-3")
    }

    @Test
    fun `pruneAMIs should return kept AMIs sorted by groupKey`() {
        val amis =
            listOf(
                cassandraAMI("ami-4", "arm64", "20240103"),
                cassandraAMI("ami-1", "amd64", "20240103"),
                baseAMI("ami-3", "arm64", "20240103"),
                baseAMI("ami-2", "amd64", "20240103"),
            )

        doReturn(amis).whenever(amiService).listPrivateAMIs(any(), any())

        val result = amiService.pruneAMIs(namePattern = NAME_PATTERN, keepCount = 1, dryRun = true)

        assertThatPruneResult(result)
            .hasKeptCount(4)
            .keptAMIsInOrder("ami-2", "ami-3", "ami-1", "ami-4")
    }

    @Test
    fun `pruneAMIs should return deleted AMIs sorted by creation date ascending`() {
        val amis =
            listOf(
                cassandraAMI("ami-2", "amd64", "20240102"),
                cassandraAMI("ami-4", "amd64", "20240104"),
                cassandraAMI("ami-1", "amd64", "20240101"),
                cassandraAMI("ami-3", "amd64", "20240103"),
            )

        doReturn(amis).whenever(amiService).listPrivateAMIs(any(), any())

        val result = amiService.pruneAMIs(namePattern = NAME_PATTERN, keepCount = 1, dryRun = true)

        assertThatPruneResult(result)
            .hasDeletedCount(3)
            .deletedAMIsInOrder("ami-1", "ami-2", "ami-3")
            .deletedAMIsAreSortedByCreationDateAscending()

        assertThat(result.deleted[0].creationDate).isBefore(result.deleted[1].creationDate)
        assertThat(result.deleted[1].creationDate).isBefore(result.deleted[2].creationDate)
    }

    @Test
    fun `pruneAMIs should handle AMIs with malformed names gracefully`() {
        val amis =
            listOf(
                cassandraAMI("ami-1", "amd64", "20240103"),
                createAMI("ami-2", "malformed-name", "amd64", "2024-01-02T00:00:00Z"),
                cassandraAMI("ami-3", "amd64", "20240101"),
            )

        doReturn(amis).whenever(amiService).listPrivateAMIs(any(), any())

        val result = amiService.pruneAMIs(namePattern = NAME_PATTERN, keepCount = 1, dryRun = true)

        assertThatPruneResult(result)
            .hasDeletedCount(1)
            .hasKeptCount(2)
    }

    @Test
    fun `pruneAMIs should handle single AMI in each group`() {
        val amis =
            listOf(
                cassandraAMI("ami-1", "amd64", "20240103"),
                cassandraAMI("ami-2", "arm64", "20240103"),
                baseAMI("ami-3", "amd64", "20240103"),
            )

        doReturn(amis).whenever(amiService).listPrivateAMIs(any(), any())

        val result = amiService.pruneAMIs(namePattern = NAME_PATTERN, keepCount = 1, dryRun = false)

        assertThatPruneResult(result)
            .hasNoDeleted()
            .hasKeptCount(3)
            .keptAMIsWithIds("ami-1", "ami-2", "ami-3")

        verify(amiService, never()).deregisterAMI(any())
    }

    // ===== Test Data Builders =====

    companion object {
        private const val DEFAULT_OWNER = "123456789012"
        private const val NAME_PREFIX = "rustyrazorblade/images/easy-db-lab"
        private const val NAME_PATTERN = "$NAME_PREFIX-*"

        fun cassandraAMI(
            id: String,
            arch: String,
            date: String,
            snapshots: List<String> = emptyList(),
        ): AMI =
            createAMI(
                id = id,
                name = "$NAME_PREFIX-cassandra-$arch-$date",
                architecture = arch,
                creationDate = "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)}T00:00:00Z",
                snapshotIds = snapshots,
            )

        fun baseAMI(
            id: String,
            arch: String,
            date: String,
            snapshots: List<String> = emptyList(),
        ): AMI =
            createAMI(
                id = id,
                name = "$NAME_PREFIX-base-$arch-$date",
                architecture = arch,
                creationDate = "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)}T00:00:00Z",
                snapshotIds = snapshots,
            )

        fun createAMI(
            id: String,
            name: String,
            architecture: String,
            creationDate: String,
            snapshotIds: List<String> = emptyList(),
            ownerId: String = DEFAULT_OWNER,
            isPublic: Boolean = false,
        ): AMI =
            AMI(
                id = id,
                name = name,
                architecture = architecture,
                creationDate = Instant.parse(creationDate),
                ownerId = ownerId,
                isPublic = isPublic,
                snapshotIds = snapshotIds,
            )
    }
}
