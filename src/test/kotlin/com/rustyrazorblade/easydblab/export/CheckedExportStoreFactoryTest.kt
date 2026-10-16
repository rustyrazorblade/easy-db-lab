package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.localstack.LocalStackStore
import com.rustyrazorblade.easydblab.localstack.S3ClientForRegion
import com.rustyrazorblade.easydblab.localstack.StoreReadResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import software.amazon.awssdk.services.s3.S3Client

/**
 * Opening the account bucket for an export checks first that the profile can read it, as
 * `local create` does: an expired SSO session or a refused bucket is reported with the profile, the
 * AWS error code and the login command, and fails the command before anything is written. A
 * readable bucket is opened with a client for its own region.
 */
class CheckedExportStoreFactoryTest {
    private val events = mutableListOf<Event>()
    private val eventBus = EventBus()
    private val regions = mutableListOf<String>()
    private var access: StoreReadResult = StoreReadResult.Readable("ap-south-1")

    private val check =
        object : LocalStackStore {
            override fun check(bucket: String): StoreReadResult = access

            override fun tenants(
                bucket: String,
                region: String,
                home: String,
            ): TenantSet = error("an export does not list tenants through the local stack")
        }

    private fun user(profile: String = "lab") =
        User(
            email = "e",
            region = "us-west-2",
            keyName = "k",
            awsProfile = profile,
            awsAccessKey = "",
            awsSecret = "",
            s3Bucket = "acct",
        )

    private fun factory(user: User = user()) =
        CheckedExportStoreFactory(
            user,
            check,
            S3ClientForRegion { region ->
                regions += region
                mock<S3Client>()
            },
            eventBus,
        )

    @BeforeEach
    fun listen() {
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
    }

    @Test
    fun `a readable bucket is opened in its own region`() {
        factory().open().use { store -> assertThat(store.bucket).isEqualTo("acct") }

        assertThat(regions).containsExactly("ap-south-1")
        assertThat(events).isEmpty()
    }

    @Test
    fun `an unreadable bucket names the profile, the AWS error and the login command, and fails before a client is made`() {
        access = StoreReadResult.Unreadable("ExpiredTokenException: The SSO session associated with this profile has expired")

        assertThatThrownBy { factory().open() }.isInstanceOf(CommandFailedException::class.java).hasMessageContaining("lab")

        val unreadable = events.filterIsInstance<Event.Export.StoreUnreadable>().single()
        assertThat(unreadable.profile).isEqualTo("lab")
        assertThat(unreadable.awsErrorCode).startsWith("ExpiredTokenException")
        assertThat(unreadable.toDisplayString()).contains("aws sso login --profile lab", "Nothing was written")
        assertThat(unreadable.isError()).isTrue()
        assertThat(regions).isEmpty()
    }

    @Test
    fun `access keys in the profile, with no AWS profile, are named as such and get no SSO hint`() {
        access = StoreReadResult.Unreadable("InvalidAccessKeyId")

        assertThatThrownBy { factory(user(profile = "")).open() }.isInstanceOf(CommandFailedException::class.java)

        val unreadable = events.filterIsInstance<Event.Export.StoreUnreadable>().single()
        assertThat(unreadable.profile).isNull()
        assertThat(unreadable.toDisplayString()).contains("access keys", "Nothing was written").doesNotContain("aws sso login")
    }
}
