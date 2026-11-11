package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorTaskDefinition
import com.rustyrazorblade.easydblab.providers.aws.withEcsRoleRetry
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.ClientException
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest
import software.amazon.awssdk.services.ecs.model.TaskDefinitionField

/**
 * Registers the account compactor's task definition, or finds the latest revision when it was built
 * from the same configuration. Registration retries while ECS cannot yet use the roles `up` has just
 * created.
 */
class CompactorTaskRegistrar {
    /** Registers [definition] and returns the new revision's ARN. */
    fun register(
        ecs: EcsClient,
        definition: CompactorTaskDefinition,
    ): String =
        withEcsRoleRetry("register-compactor-task-definition") {
            ecs
                .registerTaskDefinition(definition.request())
                .taskDefinition()
                .taskDefinitionArn()
        }

    /** The latest revision when its config hash matches [definition]'s, or a newly registered one. */
    fun latestOrRegister(
        ecs: EcsClient,
        definition: CompactorTaskDefinition,
    ): String {
        val latest =
            try {
                ecs.describeTaskDefinition(
                    DescribeTaskDefinitionRequest
                        .builder()
                        .taskDefinition(Constants.Compactor.TASK_FAMILY)
                        .include(TaskDefinitionField.TAGS)
                        .build(),
                )
            } catch (_: ClientException) {
                // No revision of the family is registered yet.
                null
            }
        val latestHash = latest?.tags()?.firstOrNull { it.key() == Constants.Compactor.CONFIG_HASH_TAG }?.value()
        return if (latest != null && latestHash == definition.configHash()) {
            latest.taskDefinition().taskDefinitionArn()
        } else {
            register(ecs, definition)
        }
    }
}
