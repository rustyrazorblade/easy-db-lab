package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.AMIService
import com.rustyrazorblade.easydblab.providers.aws.model.AMI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

class PruneAMIsTest : BaseKoinTest() {
    private lateinit var mockAMIService: AMIService
    private lateinit var outputHandler: BufferedOutputHandler

    private val oldAmi =
        AMI(
            id = "ami-old1",
            name = "rustyrazorblade/images/easy-db-lab-cassandra-amd64-20240101",
            architecture = "x86_64",
            creationDate = Instant.parse("2024-01-01T00:00:00Z"),
            ownerId = "123456789012",
            isPublic = false,
            snapshotIds = listOf("snap-old1"),
        )

    private val newAmi =
        AMI(
            id = "ami-new1",
            name = "rustyrazorblade/images/easy-db-lab-cassandra-amd64-20240601",
            architecture = "x86_64",
            creationDate = Instant.parse("2024-06-01T00:00:00Z"),
            ownerId = "123456789012",
            isPublic = false,
            snapshotIds = listOf("snap-new1"),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<AMIService> { mockAMIService }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockAMIService = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
    }

    @Nested
    inner class DryRun {
        @Test
        fun `execute shows what would be deleted in dry-run mode`() {
            whenever(mockAMIService.pruneAMIs(any(), any(), eq(true), anyOrNull()))
                .thenReturn(AMIService.PruneResult(kept = listOf(newAmi), deleted = listOf(oldAmi)))

            val command = PruneAMIs()
            command.dryRun = true
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("DRY RUN")
            assertThat(output).contains("ami-old1")
            assertThat(output).contains("Will keep 1 AMIs")
        }

        @Test
        fun `execute shows no AMIs to delete`() {
            whenever(mockAMIService.pruneAMIs(any(), any(), eq(true), anyOrNull()))
                .thenReturn(AMIService.PruneResult(kept = listOf(newAmi), deleted = emptyList()))

            val command = PruneAMIs()
            command.dryRun = true
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("No AMIs to delete")
        }
    }

    @Nested
    inner class Options {
        @Test
        fun `execute passes type filter`() {
            whenever(mockAMIService.pruneAMIs(any(), any(), eq(true), eq("cassandra")))
                .thenReturn(AMIService.PruneResult(kept = emptyList(), deleted = emptyList()))

            val command = PruneAMIs()
            command.type = "cassandra"
            command.dryRun = true
            command.execute()

            verify(mockAMIService).pruneAMIs(any(), any(), eq(true), eq("cassandra"))

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("Filtering by type: cassandra")
        }

        @Test
        fun `execute uses custom keep count`() {
            whenever(mockAMIService.pruneAMIs(any(), eq(5), eq(true), anyOrNull()))
                .thenReturn(AMIService.PruneResult(kept = emptyList(), deleted = emptyList()))

            val command = PruneAMIs()
            command.keep = 5
            command.dryRun = true
            command.execute()

            verify(mockAMIService).pruneAMIs(any(), eq(5), eq(true), anyOrNull())

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("Keeping newest 5 AMIs")
        }

        @Test
        fun `execute uses custom pattern`() {
            whenever(mockAMIService.pruneAMIs(eq("custom-pattern-*"), any(), eq(true), anyOrNull()))
                .thenReturn(AMIService.PruneResult(kept = emptyList(), deleted = emptyList()))

            val command = PruneAMIs()
            command.pattern = "custom-pattern-*"
            command.dryRun = true
            command.execute()

            verify(mockAMIService).pruneAMIs(eq("custom-pattern-*"), any(), eq(true), anyOrNull())

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("custom-pattern-*")
        }
    }

    @Nested
    inner class KeptAMIs {
        @Test
        fun `execute shows snapshot info in dry-run`() {
            whenever(mockAMIService.pruneAMIs(any(), any(), eq(true), anyOrNull()))
                .thenReturn(AMIService.PruneResult(kept = listOf(newAmi), deleted = listOf(oldAmi)))

            val command = PruneAMIs()
            command.dryRun = true
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("snap-old1")
        }

        @Test
        fun `execute shows public visibility in dry-run`() {
            val publicAmi = oldAmi.copy(isPublic = true)
            whenever(mockAMIService.pruneAMIs(any(), any(), eq(true), anyOrNull()))
                .thenReturn(AMIService.PruneResult(kept = emptyList(), deleted = listOf(publicAmi)))

            val command = PruneAMIs()
            command.dryRun = true
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("public")
        }
    }
}
