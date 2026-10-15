package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.providers.aws.AWS
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.iam.model.CreateServiceLinkedRoleRequest
import software.amazon.awssdk.services.iam.model.CreateServiceLinkedRoleResponse
import software.amazon.awssdk.services.iam.model.InvalidInputException
import software.amazon.awssdk.services.opensearch.OpenSearchClient
import software.amazon.awssdk.services.opensearch.model.CreateDomainRequest
import software.amazon.awssdk.services.opensearch.model.CreateDomainResponse
import software.amazon.awssdk.services.opensearch.model.DomainStatus
import software.amazon.awssdk.services.opensearch.model.ValidationException
import java.time.Duration

/**
 * On an account's first OpenSearch use, `ensureServiceLinkedRole` creates the role and
 * CreateDomain runs seconds later. OpenSearch does not see a new role at once (IAM is
 * eventually consistent) and refuses the domain with a ValidationException naming the
 * service-linked role. These tests pin that only that refusal, and only after this
 * process created the role, is retried, and that the retry is bounded.
 */
internal class OpenSearchServiceLinkedRoleRetryTest {
    private val iam: IamClient = mock()
    private val openSearch: OpenSearchClient = mock()
    private val service =
        OpenSearchService(
            openSearch,
            AWS(iam, mock(), mock()),
            serviceLinkedRoleRetryInterval = Duration.ofMillis(1),
        )

    private val config =
        OpenSearchDomainConfig(
            domainName = "test-domain",
            subnetId = "subnet-1",
            securityGroupIds = listOf("sg-1"),
            accountId = "000000000000",
            region = "us-west-2",
        )

    private val created =
        CreateDomainResponse
            .builder()
            .domainStatus(
                DomainStatus
                    .builder()
                    .domainName("test-domain")
                    .domainId("000000000000/test-domain")
                    .processing(true)
                    .deleted(false)
                    .build(),
            ).build()

    private fun validation(message: String): ValidationException =
        ValidationException
            .builder()
            .statusCode(400)
            .message(message)
            .awsErrorDetails(
                AwsErrorDetails
                    .builder()
                    .errorCode("ValidationException")
                    .errorMessage(message)
                    .build(),
            ).build() as ValidationException

    private val roleNotVisible =
        validation(
            "Before you can proceed, you must enable a service-linked role to give " +
                "Amazon OpenSearch Service permissions to access your VPC.",
        )

    private fun roleIsCreated() {
        whenever(iam.createServiceLinkedRole(any<CreateServiceLinkedRoleRequest>()))
            .thenReturn(CreateServiceLinkedRoleResponse.builder().build())
    }

    private fun roleAlreadyExists() {
        whenever(iam.createServiceLinkedRole(any<CreateServiceLinkedRoleRequest>()))
            .thenThrow(InvalidInputException.builder().message("has been taken").build())
    }

    @Test
    fun `a just-created role is retried until OpenSearch sees it`() {
        roleIsCreated()
        whenever(openSearch.createDomain(any<CreateDomainRequest>()))
            .thenThrow(roleNotVisible)
            .thenThrow(roleNotVisible)
            .thenReturn(created)

        service.ensureServiceLinkedRole()
        val result = service.createDomain(config)

        assertThat(result.domainName).isEqualTo("test-domain")
        verify(openSearch, times(3)).createDomain(any<CreateDomainRequest>())
    }

    @Test
    fun `an unrelated validation error fails at once even after creating the role`() {
        roleIsCreated()
        whenever(openSearch.createDomain(any<CreateDomainRequest>()))
            .thenThrow(validation("Invalid instance type: t3.huge.search"))

        service.ensureServiceLinkedRole()

        assertThatThrownBy { service.createDomain(config) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("Invalid instance type")
        verify(openSearch, times(1)).createDomain(any<CreateDomainRequest>())
    }

    @Test
    fun `a role that already existed is not retried`() {
        roleAlreadyExists()
        whenever(openSearch.createDomain(any<CreateDomainRequest>())).thenThrow(roleNotVisible)

        service.ensureServiceLinkedRole()

        assertThatThrownBy { service.createDomain(config) }
            .isInstanceOf(ValidationException::class.java)
        verify(openSearch, times(1)).createDomain(any<CreateDomainRequest>())
    }

    @Test
    fun `the retry gives up after its bound`() {
        roleIsCreated()
        whenever(openSearch.createDomain(any<CreateDomainRequest>())).thenThrow(roleNotVisible)

        service.ensureServiceLinkedRole()

        assertThatThrownBy { service.createDomain(config) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("service-linked role")
        verify(openSearch, times(Constants.OpenSearch.SERVICE_LINKED_ROLE_MAX_ATTEMPTS))
            .createDomain(any<CreateDomainRequest>())
    }
}
