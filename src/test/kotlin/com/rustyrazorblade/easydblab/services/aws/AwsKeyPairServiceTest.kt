package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.CreateKeyPairRequest
import software.amazon.awssdk.services.ec2.model.CreateKeyPairResponse
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Key pair generation for profile setup: the private key lands in the profile directory readable
 * only by its owner, and a permission failure is explained once, then rethrown as already reported.
 */
class AwsKeyPairServiceTest {
    private val emitted = mutableListOf<Event>()
    private val eventBus =
        EventBus().also {
            it.addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        emitted.add(envelope.event)
                    }

                    override fun close() = Unit
                },
            )
        }
    private val ec2: Ec2Client = mock()
    private val service = AwsKeyPairService(eventBus)

    @Test
    fun `the private key is saved owner-only and the tagged key name returned`(
        @TempDir profileDir: File,
    ) {
        whenever(ec2.createKeyPair(any<CreateKeyPairRequest>()))
            .thenReturn(CreateKeyPairResponse.builder().keyMaterial("PRIVATE-KEY").build())

        val keyName = service.generate(profileDir, ec2)

        val secret = File(profileDir, "secret.pem")
        assertThat(secret.readText()).isEqualTo("PRIVATE-KEY")
        assertThat(Files.getPosixFilePermissions(secret.toPath()))
            .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val request = argumentCaptor<CreateKeyPairRequest>()
        verify(ec2).createKeyPair(request.capture())
        assertThat(request.firstValue.keyName()).isEqualTo(keyName).startsWith("easy-db-lab-")
        assertThat(
            request.firstValue
                .tagSpecifications()
                .single()
                .tags()
                .single()
                .key(),
        ).isEqualTo("easy_cass_lab")
    }

    @Test
    fun `a permission error shows the policies once and is rethrown as already reported`(
        @TempDir profileDir: File,
    ) {
        val denied =
            Ec2Exception
                .builder()
                .statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("UnauthorizedOperation").build())
                .message("not allowed")
                .build()
        whenever(ec2.createKeyPair(any<CreateKeyPairRequest>())).thenThrow(denied)

        assertThatThrownBy { service.generate(profileDir, ec2) }
            .isInstanceOf(CommandFailedException::class.java)
            .hasCause(denied)

        assertThat(emitted.filterIsInstance<Event.Setup.AwsPermissionError>()).hasSize(1)
        assertThat(emitted.filterIsInstance<Event.Setup.AwsPermissionPolicyDisplay>()).isNotEmpty
        assertThat(emitted).contains(Event.Setup.AwsPermissionPolicyFooter)
    }

    @Test
    fun `any other EC2 failure is rethrown untouched`(
        @TempDir profileDir: File,
    ) {
        val other =
            Ec2Exception
                .builder()
                .statusCode(500)
                .message("boom")
                .build()
        whenever(ec2.createKeyPair(any<CreateKeyPairRequest>())).thenThrow(other)

        assertThatThrownBy { service.generate(profileDir, ec2) }.isSameAs(other)

        assertThat(emitted.filterIsInstance<Event.Setup.AwsPermissionError>()).isEmpty()
    }
}
