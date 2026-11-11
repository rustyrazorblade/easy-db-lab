package com.rustyrazorblade.easydblab.commands.observability

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.services.aws.CompactorContainerState
import com.rustyrazorblade.easydblab.services.aws.CompactorService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import com.rustyrazorblade.easydblab.services.aws.CompactorStatus as Status

/**
 * The compactor commands work outside a cluster workspace: they take the account bucket from the
 * profile and never read a cluster's state. `status` prints what the service reports and changes
 * nothing.
 */
class CompactorCommandsTest : BaseKoinTest() {
    private val compactor = mock<CompactorService>()
    private val clusterStateManager = mock<ClusterStateManager>()
    private var bucket = "easy-db-lab-acct"

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<CompactorService> { compactor }
                single<ClusterStateManager> { clusterStateManager }
                single {
                    User(
                        email = "test@example.com",
                        region = "us-west-2",
                        keyName = "test-key",
                        awsProfile = "",
                        awsAccessKey = "k",
                        awsSecret = "s",
                        axonOpsOrg = "",
                        axonOpsKey = "",
                        s3Bucket = bucket,
                    )
                }
            },
        )

    private fun stdout(block: () -> Unit): String {
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return captured.toString()
    }

    @Test
    fun `start and stop act on the profile's account bucket without a cluster state`() {
        CompactorStart().execute()
        CompactorStop().execute()

        verify(compactor).ensureRunning("easy-db-lab-acct")
        verify(compactor).stop("easy-db-lab-acct")
        verifyNoInteractions(clusterStateManager)
    }

    @Test
    fun `status prints the state, the task and its recent log lines`() {
        whenever(compactor.status("easy-db-lab-acct")).thenReturn(
            Status(
                region = "eu-west-1",
                exists = true,
                desiredCount = 1,
                runningCount = 1,
                taskId = "abc123",
                taskStatus = "RUNNING",
                logLines = listOf("[mimir-compactor] compaction done"),
            ),
        )

        val output = stdout { CompactorStatus().execute() }

        assertThat(output)
            .contains("Account compactor: running (region eu-west-1)")
            .contains("1 running, 0 pending, 1 desired")
            .contains("abc123 (RUNNING)")
            .contains("[mimir-compactor] compaction done")
        verifyNoInteractions(clusterStateManager)
    }

    /** A task that crashes in a loop reads as failing, with why it stopped, never as running. */
    @Test
    fun `status shows a crash-looping service as failing, with the stop reason, each container's exit and the service events`() {
        whenever(compactor.status("easy-db-lab-acct")).thenReturn(
            Status(
                region = "eu-west-1",
                exists = true,
                desiredCount = 1,
                runningCount = 0,
                taskId = "def456",
                taskStatus = "STOPPED",
                stoppedReason = "Essential container in task exited",
                stopCode = "EssentialContainerExited",
                containers =
                    listOf(
                        CompactorContainerState("config", "STOPPED", 0, ""),
                        CompactorContainerState("loki-compactor", "STOPPED", 1, "CannotPullContainerError"),
                    ),
                serviceEvents = listOf("2026-09-27T10:00:00Z (service easy-db-lab-compactor) has started 1 tasks"),
            ),
        )

        val output = stdout { CompactorStatus().execute() }

        assertThat(output)
            .contains("Account compactor: failing (region eu-west-1)")
            .contains("Stopped: EssentialContainerExited: Essential container in task exited")
            .contains("loki-compactor: STOPPED exit 1 (CannotPullContainerError)")
            .contains("config: STOPPED exit 0")
            .contains("has started 1 tasks")
    }

    @Test
    fun `a profile with no account bucket is refused with the way to make one`() {
        bucket = ""

        assertThatThrownBy { CompactorStart().execute() }.hasMessageContaining("easy-db-lab up")
    }
}
