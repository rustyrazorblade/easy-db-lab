package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.EventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.CreateKeyPairRequest
import software.amazon.awssdk.services.ec2.model.CreateKeyPairResponse
import software.amazon.awssdk.services.ec2.model.ResourceType
import java.io.File

/**
 * Tests for [User.generateAwsKeyPair]: the key pair carries both marker tags, and its private key
 * lands in the profile directory.
 */
class UserKeyPairTest : BaseKoinTest() {
    @Test
    fun `the key pair carries both marker tags and its key is written to the profile`() {
        val ec2Client = mock<Ec2Client>()
        whenever(ec2Client.createKeyPair(any<CreateKeyPairRequest>()))
            .thenReturn(CreateKeyPairResponse.builder().keyMaterial("PRIVATE KEY").build())

        val keyName = User.generateAwsKeyPair(context, ec2Client, get<EventBus>())

        val request = argumentCaptor<CreateKeyPairRequest>()
        verify(ec2Client).createKeyPair(request.capture())
        val spec = request.firstValue.tagSpecifications().single()
        assertThat(spec.resourceType()).isEqualTo(ResourceType.KEY_PAIR)
        assertThat(spec.tags().associate { it.key() to it.value() })
            .containsEntry(Constants.Vpc.LEGACY_TAG_KEY, Constants.Vpc.TAG_VALUE)
            .containsEntry(Constants.Vpc.TAG_KEY, Constants.Vpc.TAG_VALUE)
        assertThat(request.firstValue.keyName()).isEqualTo(keyName)
        assertThat(File(context.profileDir, "secret.pem")).hasContent("PRIVATE KEY")
    }
}
