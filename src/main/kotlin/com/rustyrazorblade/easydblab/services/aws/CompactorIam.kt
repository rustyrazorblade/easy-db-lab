package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.providers.aws.AWSPolicy
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.iam.model.AttachRolePolicyRequest
import software.amazon.awssdk.services.iam.model.CreateRoleRequest
import software.amazon.awssdk.services.iam.model.CreateServiceLinkedRoleRequest
import software.amazon.awssdk.services.iam.model.GetRoleRequest
import software.amazon.awssdk.services.iam.model.InvalidInputException
import software.amazon.awssdk.services.iam.model.NoSuchEntityException
import software.amazon.awssdk.services.iam.model.PutRolePolicyRequest

/**
 * The ARNs of the account compactor's two roles.
 *
 * @property taskRoleArn the role the containers run as.
 * @property executionRoleArn the role ECS pulls images and ships logs with.
 */
data class CompactorRoles(
    val taskRoleArn: String,
    val executionRoleArn: String,
)

/**
 * Finds or creates the IAM the account compactor needs: its task role, which alone may delete
 * under the compacted roots; its execution role; and ECS's service-linked role. IAM is global, so
 * the profile's client serves every region.
 */
class CompactorIam(
    private val iam: IamClient,
) {
    private companion object {
        val log = KotlinLogging.logger {}
        const val TASK_POLICY_NAME = "CompactorS3Access"
        const val ECS_SERVICE = "ecs.amazonaws.com"
    }

    /** Ensures both roles and their policies, and ECS's service-linked role. */
    fun ensure(): CompactorRoles {
        val taskRoleArn = ensureRole(Constants.Compactor.TASK_ROLE, "easy-db-lab account compactor task")
        withIamRetry("compactor-task-policy") {
            iam.putRolePolicy(
                PutRolePolicyRequest
                    .builder()
                    .roleName(Constants.Compactor.TASK_ROLE)
                    .policyName(TASK_POLICY_NAME)
                    .policyDocument(AWSPolicy.Inline.CompactorTaskAccess.toJson())
                    .build(),
            )
        }
        val executionRoleArn = ensureRole(Constants.Compactor.EXECUTION_ROLE, "easy-db-lab account compactor execution")
        withIamRetry("compactor-execution-policy") {
            iam.attachRolePolicy(
                AttachRolePolicyRequest
                    .builder()
                    .roleName(Constants.Compactor.EXECUTION_ROLE)
                    .policyArn(AWSPolicy.Managed.ECSTaskExecution.arn)
                    .build(),
            )
        }
        ensureEcsServiceLinkedRole()
        return CompactorRoles(taskRoleArn, executionRoleArn)
    }

    private fun ensureRole(
        name: String,
        description: String,
    ): String =
        try {
            iam.getRole(GetRoleRequest.builder().roleName(name).build()).role().arn()
        } catch (_: NoSuchEntityException) {
            log.info { "Creating IAM role $name" }
            iam
                .createRole(
                    CreateRoleRequest
                        .builder()
                        .roleName(name)
                        .assumeRolePolicyDocument(AWSPolicy.ECSTasksTrust.toJson())
                        .description(description)
                        .build(),
                ).role()
                .arn()
        }

    private fun ensureEcsServiceLinkedRole() {
        try {
            iam.createServiceLinkedRole(CreateServiceLinkedRoleRequest.builder().awsServiceName(ECS_SERVICE).build())
        } catch (e: InvalidInputException) {
            // IAM answers "has been taken" when the role already exists.
            if (e.message?.contains("has been taken") != true) throw e
        }
    }

    private fun withIamRetry(
        name: String,
        block: () -> Unit,
    ) = Retry.decorateRunnable(Retry.of(name, RetryUtil.createIAMRetryConfig()), block).run()
}
