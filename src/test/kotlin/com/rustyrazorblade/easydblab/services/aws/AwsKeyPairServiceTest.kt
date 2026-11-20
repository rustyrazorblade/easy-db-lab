package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.exceptions.ConfigurationException
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import com.rustyrazorblade.easydblab.providers.aws.AWSClientFactory
import com.rustyrazorblade.easydblab.providers.aws.AWSPolicy
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
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest
import software.amazon.awssdk.services.ec2.model.DescribeKeyPairsRequest
import software.amazon.awssdk.services.ec2.model.DescribeKeyPairsResponse
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ec2.model.KeyPairInfo
import software.amazon.awssdk.services.ec2.model.Tag
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
    private val settingsFile = File("/profiles/lab/settings.yaml")
    private val service = AwsKeyPairService(eventBus, mock(), AwsErrorHandler(), settingsFile)

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
                .associate { it.key() to it.value() },
        ).containsExactlyInAnyOrderEntriesOf(mapOf("easy_cass_lab" to "1", "easy_db_lab" to "1"))
    }

    private fun existingKeyPair(vararg tags: Pair<String, String>) {
        val keyPair =
            KeyPairInfo
                .builder()
                .keyName("lab-key")
                .keyPairId("key-0abc")
                .tags(
                    tags.map { (key, value) ->
                        Tag
                            .builder()
                            .key(key)
                            .value(value)
                            .build()
                    },
                ).build()
        whenever(ec2.describeKeyPairs(any<DescribeKeyPairsRequest>()))
            .thenReturn(DescribeKeyPairsResponse.builder().keyPairs(keyPair).build())
    }

    @Test
    fun `a key pair with only the legacy marker gains the new one and nothing else`() {
        existingKeyPair("easy_cass_lab" to "1", "Purpose" to "lab")

        service.ensureMarkerTags("lab-key", ec2)

        val request = argumentCaptor<CreateTagsRequest>()
        verify(ec2).createTags(request.capture())
        assertThat(request.firstValue.resources()).containsExactly("key-0abc")
        assertThat(request.firstValue.tags().associate { it.key() to it.value() })
            .containsExactlyEntriesOf(mapOf("easy_db_lab" to "1"))
    }

    @Test
    fun `a key pair that carries both markers is left alone`() {
        existingKeyPair("easy_db_lab" to "1", "easy_cass_lab" to "1")

        service.ensureMarkerTags("lab-key", ec2)

        verify(ec2, never()).createTags(any<CreateTagsRequest>())
    }

    /** The IAM policy allows tagging only a resource that carries the legacy marker; the tool never marked this one. */
    @Test
    fun `a key pair without the legacy marker is left alone`() {
        existingKeyPair("Purpose" to "lab")

        service.ensureMarkerTags("lab-key", ec2)

        verify(ec2, never()).createTags(any<CreateTagsRequest>())
    }

    @Test
    fun `a key pair the profile names that does not exist fails naming it`() {
        val notFound =
            Ec2Exception
                .builder()
                .statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("InvalidKeyPair.NotFound").build())
                .build()
        whenever(ec2.describeKeyPairs(any<DescribeKeyPairsRequest>())).thenThrow(notFound)

        assertThatThrownBy { service.ensureMarkerTags("lab-key", ec2) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("lab-key")
            .hasMessageContaining("/profiles/lab/settings.yaml")
            .hasCause(notFound)
    }

    @Test
    fun `any other failure looking up the key pair is rethrown untouched`() {
        val denied =
            Ec2Exception
                .builder()
                .statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("UnauthorizedOperation").build())
                .build()
        whenever(ec2.describeKeyPairs(any<DescribeKeyPairsRequest>())).thenThrow(denied)

        assertThatThrownBy { service.ensureMarkerTags("lab-key", ec2) }.isSameAs(denied)
    }

    @Test
    fun `a profile with no key pair yet asks EC2 nothing`() {
        val factory: AWSClientFactory = mock()
        val user = User("a@b.c", "us-west-2", keyName = "", awsProfile = "lab", awsAccessKey = "", awsSecret = "")

        AwsKeyPairService(eventBus, factory, AwsErrorHandler(), settingsFile).ensureMarkerTags(user)

        verify(factory, never()).createEc2ClientWithProfile(any(), any())
    }

    @Test
    fun `the backfill uses the profile's credentials and closes the client it built`() {
        val factory: AWSClientFactory = mock()
        whenever(factory.createEc2ClientWithProfile("lab", "us-west-2")).thenReturn(ec2)
        existingKeyPair("easy_cass_lab" to "1")
        val user = User("a@b.c", "us-west-2", keyName = "lab-key", awsProfile = "lab", awsAccessKey = "", awsSecret = "")

        AwsKeyPairService(eventBus, factory, AwsErrorHandler(), settingsFile).ensureMarkerTags(user)

        verify(ec2).createTags(any<CreateTagsRequest>())
        verify(ec2).close()
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

        val error = emitted.filterIsInstance<Event.Command.ProviderError>().single()
        assertThat(error.kind).isEqualTo(ProviderErrorKind.PERMISSION_DENIED)
        assertThat(error.remedy).contains(AWSPolicy.UserIAM.EC2_POLICY)
        assertThat(error.response?.errorCode).isEqualTo("UnauthorizedOperation")
        // The policy listing follows the provider error, one entry per lab policy.
        assertThat(emitted.indexOf(error)).isLessThan(emitted.indexOfFirst { it is Event.Setup.AwsPermissionPolicyDisplay })
        assertThat(emitted.filterIsInstance<Event.Setup.AwsPermissionPolicyDisplay>().map { it.name })
            .containsExactlyElementsOf(AWSPolicy.UserIAM.ALL_NAMES)
        val footer = emitted.filterIsInstance<Event.Setup.AwsPermissionPolicyFooter>().single()
        assertThat(footer.toDisplayString())
            .contains("Create ${AWSPolicy.UserIAM.ALL_NAMES.size} managed policies")
            .contains(AWSPolicy.UserIAM.OPENSEARCH_POLICY)
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

        assertThat(emitted.filterIsInstance<Event.Command.ProviderError>().single().kind)
            .isEqualTo(ProviderErrorKind.PERMISSION_DENIED)
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
        assertThat(emitted.filterIsInstance<Event.Command.ProviderError>()).hasSize(1)
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

        assertThat(emitted.filterIsInstance<Event.Command.ProviderError>()).isEmpty()
    }

    @Test
    fun `a client the service built from a profile is closed after success`(
        @TempDir profileDir: File,
    ) {
        val factory: AWSClientFactory = mock()
        whenever(factory.createEc2ClientWithProfile("lab", "us-west-2")).thenReturn(ec2)
        whenever(ec2.createKeyPair(any<CreateKeyPairRequest>()))
            .thenReturn(CreateKeyPairResponse.builder().keyMaterial("K").build())

        AwsKeyPairService(eventBus, factory, AwsErrorHandler(), settingsFile).generate(profileDir, "us-west-2", "lab", "", "")

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
            AwsKeyPairService(eventBus, factory, AwsErrorHandler(), settingsFile).generate(profileDir, "us-west-2", "", "key", "secret")
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
