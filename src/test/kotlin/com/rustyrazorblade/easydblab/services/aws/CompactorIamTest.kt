package com.rustyrazorblade.easydblab.services.aws

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.iam.model.CreateServiceLinkedRoleRequest
import software.amazon.awssdk.services.iam.model.GetRoleRequest
import software.amazon.awssdk.services.iam.model.GetRoleResponse
import software.amazon.awssdk.services.iam.model.NoSuchEntityException
import software.amazon.awssdk.services.iam.model.Role

/**
 * When `up` creates ECS's service-linked role: only when IAM reports it missing by error code, so
 * an existing role is never created twice and nothing depends on an error message.
 */
class CompactorIamTest {
    private val iam = mock<IamClient>()
    private val serviceLinkedRole = "AWSServiceRoleForECS"

    @BeforeEach
    fun rolesExist() {
        whenever(iam.getRole(any<GetRoleRequest>())).thenAnswer { invocation ->
            val name = invocation.getArgument<GetRoleRequest>(0).roleName()
            GetRoleResponse
                .builder()
                .role(
                    Role
                        .builder()
                        .roleName(name)
                        .arn("arn:aws:iam::1:role/$name")
                        .build(),
                ).build()
        }
    }

    @Test
    fun `an existing service-linked role is not created again`() {
        CompactorIam(iam).ensure()

        verify(iam, never()).createServiceLinkedRole(any<CreateServiceLinkedRoleRequest>())
    }

    @Test
    fun `a missing service-linked role is created for ECS`() {
        doThrow(NoSuchEntityException.builder().message("not found").build())
            .whenever(iam)
            .getRole(argThat<GetRoleRequest> { roleName() == serviceLinkedRole })

        CompactorIam(iam).ensure()

        val created = argumentCaptor<CreateServiceLinkedRoleRequest>()
        verify(iam).createServiceLinkedRole(created.capture())
        assertThat(created.firstValue.awsServiceName()).isEqualTo("ecs.amazonaws.com")
    }
}
