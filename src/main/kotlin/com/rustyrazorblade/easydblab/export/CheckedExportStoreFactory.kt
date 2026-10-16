package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.export.query.QueryStoreAccess
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.localstack.LocalStackStore
import com.rustyrazorblade.easydblab.localstack.S3ClientForRegion
import com.rustyrazorblade.easydblab.localstack.StoreReadResult

/**
 * Opens the account bucket for an export after the same read check `local create` runs
 * ([LocalStackStore.check]: the bucket's region, a `HeadBucket` and a one-key list). Every export
 * opens its store before it writes anything, so missing or expired credentials fail here, reported as
 * [Event.Export.StoreUnreadable] with the profile, the AWS error and the login command, and no zip or
 * partial file is ever made. Without the check the SDK's own exception (an expired SSO session is
 * `ExpiredTokenException`) reached the operator raw.
 *
 * @property user the easy-db-lab profile: the AWS profile and the account bucket.
 * @property check the read check.
 * @property clients makes the client for the bucket's own region.
 * @property eventBus reports an unreadable bucket.
 */
class CheckedExportStoreFactory(
    private val user: User,
    private val check: LocalStackStore,
    private val clients: S3ClientForRegion,
    private val eventBus: EventBus,
) : ExportStoreFactory {
    override fun open(): ExportStore = readable().let { (bucket, region) -> S3ExportStore(clients.forRegion(region), bucket) }

    /**
     * The bucket for a query export, whose reader stack signs with an AWS profile from `~/.aws`: one
     * with access keys and no AWS profile fails first ([Event.Export.AwsProfileRequired]).
     */
    fun openForQuery(): QueryStoreAccess {
        val profile =
            user.awsProfile.ifBlank {
                eventBus.emit(Event.Export.AwsProfileRequired)
                throw CommandFailedException("A query export needs an AWS profile in the easy-db-lab profile")
            }
        val (bucket, region) = readable()
        return QueryStoreAccess(S3ExportStore(clients.forRegion(region), bucket), bucket, region, profile)
    }

    /** The bucket and its region, once the check found it readable. */
    private fun readable(): Pair<String, String> {
        val bucket = user.s3Bucket.ifBlank { error("No account bucket in the profile. Run 'easy-db-lab up' once to create it.") }
        return when (val access = check.check(bucket)) {
            is StoreReadResult.Readable -> bucket to access.region
            is StoreReadResult.Unreadable -> {
                val profile = user.awsProfile.ifBlank { null }
                eventBus.emit(Event.Export.StoreUnreadable(profile, access.awsErrorCode))
                throw CommandFailedException(
                    "The account bucket is not readable with ${profile?.let { "profile $it" } ?: "the profile's access keys"}",
                )
            }
        }
    }
}
