package com.rustyrazorblade.easydblab.commands.observability

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.compactorlease.LeaseRecord
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.services.aws.CompactorLease
import com.rustyrazorblade.easydblab.services.aws.CompactorLeaseReader
import org.koin.core.component.inject
import picocli.CommandLine.Command
import kotlin.time.Duration.Companion.seconds

/**
 * Prints who holds the account compactor's lease. It works outside a cluster workspace: the account
 * bucket comes from the profile, not from a cluster's state. It changes nothing; the compactor runs
 * in a pod on every cluster, and the lease decides which one compacts.
 */
@RequireProfileSetup
@Command(
    name = "status",
    description = ["Show which cluster's account compactor holds the store lease"],
    mixinStandardHelpOptions = true,
)
class CompactorStatus : PicoBaseCommand() {
    private val user: User by inject()
    private val leaseReader: CompactorLeaseReader by inject()

    override fun execute() {
        val bucket = user.s3Bucket.ifBlank { error("No account bucket in the profile. Run 'easy-db-lab up' once to create it.") }
        println(
            when (val lease = leaseReader.read(bucket)) {
                is CompactorLease.None -> "No compactor holds the lease (bucket region ${lease.region})"
                is CompactorLease.Held -> held(lease)
                is CompactorLease.Unreadable -> unreadable(lease)
            },
        )
    }

    private fun unreadable(lease: CompactorLease.Unreadable): String =
        """
        |The account compactor lease object cannot be read (bucket region ${lease.region}). No pod compacts while it is there.
        |Its content:
        |${lease.content}
        |Delete ${LeaseRecord.KEY} from the account bucket by hand; a compactor pod then creates a fresh lease.
        """.trimMargin()

    private fun held(lease: CompactorLease.Held): String {
        val ttl = lease.record.ttlSeconds
        val expired = if (lease.age > ttl.seconds) " (expired: older than its ${ttl}s TTL)" else ""
        return """
            |Account compactor lease (bucket region ${lease.region})
            |Holder: ${lease.record.holder}
            |Sequence: ${lease.record.sequence}
            |Renewed: ${lease.record.renewedAt}, ${lease.age} ago$expired
            """.trimMargin()
    }
}
