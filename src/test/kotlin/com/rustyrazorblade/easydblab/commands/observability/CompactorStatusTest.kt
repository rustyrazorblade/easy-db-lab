package com.rustyrazorblade.easydblab.commands.observability

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.compactorlease.LeaseRecord
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.services.aws.CompactorLeaseReader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * `observability compactor status` works outside a cluster workspace: it takes the account bucket
 * from the profile, reads the lease in the bucket's region, and never reads a cluster's state or
 * writes anything. There is nothing to start or stop.
 */
class CompactorStatusTest : BaseKoinTest() {
    private val clusterStateManager = mock<ClusterStateManager>()
    private var bucket = "easy-db-lab-acct"
    private var lease: String? = null
    private val readsAsked = mutableListOf<Pair<String, String>>()
    private val now = Instant.parse("2026-11-07T18:02:30Z")

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { clusterStateManager }
                single {
                    CompactorLeaseReader(
                        regionOf = { "eu-west-1" },
                        leaseIn = { region, bucket -> lease.also { readsAsked += region to bucket } },
                        clock = FixedClock(now),
                    )
                }
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

    /** A clock that always reads [now]. */
    private class FixedClock(
        private val now: Instant,
    ) : Clock {
        override fun now(): Instant = now
    }

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
    fun `status prints the holder, the sequence, the renewal time and the age, read in the bucket's region`() {
        lease = LeaseRecord("lab-a/account-compactor-7d9f", 42, 120, Instant.parse("2026-11-07T18:02:00Z")).toJson()

        val output = stdout { CompactorStatus().execute() }

        assertThat(output)
            .contains("lab-a/account-compactor-7d9f")
            .contains("Sequence: 42")
            .contains("2026-11-07T18:02:00Z")
            .contains("30s ago")
            .contains("eu-west-1")
        assertThat(readsAsked).containsExactly("eu-west-1" to "easy-db-lab-acct")
        verifyNoInteractions(clusterStateManager)
    }

    /** A lease no one renewed for longer than its TTL is shown as expired, so a dead holder is plain. */
    @Test
    fun `status marks a lease older than its TTL as expired`() {
        lease = LeaseRecord("lab-a/account-compactor-7d9f", 42, 120, Instant.parse("2026-11-07T17:52:30Z")).toJson()

        val output = stdout { CompactorStatus().execute() }

        assertThat(output).contains("10m ago").contains("expired")
    }

    @Test
    fun `status says no compactor holds the lease when there is none`() {
        val output = stdout { CompactorStatus().execute() }

        assertThat(output).contains("No compactor holds the lease").contains("eu-west-1")
    }

    /**
     * A lease body no agent can parse stops compaction everywhere, and only an operator can clear
     * it, so status shows the body and the way out instead of failing.
     */
    @Test
    fun `status on a lease it cannot parse shows the body and how to clear it`() {
        lease = """{"holder":"""

        val output = stdout { CompactorStatus().execute() }

        assertThat(output)
            .contains("cannot be read")
            .contains("""{"holder":""")
            .contains("leases/compactor.json")
            .contains("eu-west-1")
    }

    /** Well-formed JSON that breaks a lease invariant is just as unusable to the agents. */
    @Test
    fun `status on a lease with a negative sequence reports it cannot be read`() {
        lease = """{"holder":"h","sequence":-1,"ttlSeconds":120,"renewedAt":"2026-11-07T18:00:00Z"}"""

        val output = stdout { CompactorStatus().execute() }

        assertThat(output).contains("cannot be read").contains("leases/compactor.json")
    }

    @Test
    fun `there is no start or stop`() {
        val subcommands = CommandLine(Compactor()).subcommands.keys

        assertThat(subcommands).doesNotContain("start", "stop").contains("status")
    }

    @Test
    fun `a profile with no account bucket is refused with the way to make one`() {
        bucket = ""

        assertThatThrownBy { CompactorStatus().execute() }.hasMessageContaining("easy-db-lab up")
        assertThat(readsAsked).isEmpty()
    }
}
