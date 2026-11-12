package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.providers.aws.AWSClientFactory
import com.rustyrazorblade.easydblab.providers.aws.AwsErrorHandler
import com.rustyrazorblade.easydblab.services.CommandFailureReporter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
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
import java.nio.file.attribute.PosixFilePermissions

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
    private val service = AwsKeyPairService(eventBus, mock(), AwsErrorHandler())

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
    fun `AccessDenied from EC2 shows the policies once and is rethrown as already reported`(
        @TempDir profileDir: File,
    ) {
        val denied =
            Ec2Exception
                .builder()
                .statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
                .message("not allowed")
                .build()
        whenever(ec2.createKeyPair(any<CreateKeyPairRequest>())).thenThrow(denied)

        assertThatThrownBy { service.generate(profileDir, ec2) }
            .isInstanceOf(CommandFailedException::class.java)
            .hasCause(denied)

        assertThat(emitted.filterIsInstance<Event.Setup.AwsPermissionError>()).hasSize(1)
        assertThat(File(profileDir, "secret.pem")).doesNotExist()
    }

    @Test
    fun `the user sees one policy block when the reporter handles the rethrown denial`(
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
        val failure = runCatching { service.generate(profileDir, ec2) }.exceptionOrNull()!!
        val afterService = emitted.size

        CommandFailureReporter(eventBus, listOf(AwsErrorHandler())).report(failure)

        assertThat(emitted).hasSize(afterService)
        assertThat(emitted.filterIsInstance<Event.Command.ProviderError>()).isEmpty()
        assertThat(emitted.filterIsInstance<Event.Setup.AwsPermissionError>()).hasSize(1)
    }

    @Test
    fun `an existing secret file is replaced and ends up owner-only`(
        @TempDir profileDir: File,
    ) {
        val secret = File(profileDir, "secret.pem")
        secret.writeText("OLD-KEY")
        Files.setPosixFilePermissions(secret.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        whenever(ec2.createKeyPair(any<CreateKeyPairRequest>()))
            .thenReturn(CreateKeyPairResponse.builder().keyMaterial("NEW-KEY").build())

        service.generate(profileDir, ec2)

        assertThat(secret.readText()).isEqualTo("NEW-KEY")
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(secret.toPath())))
            .isEqualTo("rw-------")
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

    @Test
    fun `a client the service built from a profile is closed after success`(
        @TempDir profileDir: File,
    ) {
        val factory: AWSClientFactory = mock()
        whenever(factory.createEc2ClientWithProfile("lab", "us-west-2")).thenReturn(ec2)
        whenever(ec2.createKeyPair(any<CreateKeyPairRequest>()))
            .thenReturn(CreateKeyPairResponse.builder().keyMaterial("K").build())

        AwsKeyPairService(eventBus, factory, AwsErrorHandler()).generate(profileDir, "us-west-2", "lab", "", "")

        verify(ec2).close()
    }

    @Test
    fun `a client the service built from static keys is closed after failure`(
        @TempDir profileDir: File,
    ) {
        val factory: AWSClientFactory = mock()
        whenever(factory.createEc2Client("key", "secret", "us-west-2")).thenReturn(ec2)
        whenever(ec2.createKeyPair(any<CreateKeyPairRequest>())).thenThrow(RuntimeException("boom"))

        assertThatThrownBy {
            AwsKeyPairService(eventBus, factory, AwsErrorHandler()).generate(profileDir, "us-west-2", "", "key", "secret")
        }.hasMessage("boom")

        verify(ec2).close()
    }

    @Test
    fun `a client the caller injected is never closed by the service`(
        @TempDir profileDir: File,
    ) {
        whenever(ec2.createKeyPair(any<CreateKeyPairRequest>()))
            .thenReturn(CreateKeyPairResponse.builder().keyMaterial("K").build())

        service.generate(profileDir, ec2)

        verify(ec2, never()).close()
    }
}
