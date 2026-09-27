package com.rustyrazorblade.easydblab.commands.observability

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.services.aws.CompactorService
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * A compactor command. It works outside a cluster workspace: the account bucket comes from the
 * profile, not from a cluster's state.
 */
abstract class CompactorCommand : PicoBaseCommand() {
    protected val compactorService: CompactorService by inject()
    private val user: User by inject()

    /** The profile's account bucket. */
    protected fun accountBucket(): String =
        user.s3Bucket.ifBlank { error("No account bucket in the profile. Run 'easy-db-lab up' once to create it.") }
}

/** Starts the account compactor as `up` does: creates it when missing, starts it when stopped. */
@RequireProfileSetup
@Command(
    name = "start",
    description = ["Start the account compactor (creates it when missing)"],
    mixinStandardHelpOptions = true,
)
class CompactorStart : CompactorCommand() {
    override fun execute() = compactorService.ensureRunning(accountBucket())
}

/** Stops the account compactor by setting its desired count to 0. */
@RequireProfileSetup
@Command(
    name = "stop",
    description = ["Stop the account compactor"],
    mixinStandardHelpOptions = true,
)
class CompactorStop : CompactorCommand() {
    override fun execute() = compactorService.stop(accountBucket())
}

/** Prints the compactor's state, its current or last task, and that task's recent log lines. Changes nothing. */
@RequireProfileSetup
@Command(
    name = "status",
    description = ["Show the account compactor's state, task and recent log lines"],
    mixinStandardHelpOptions = true,
)
class CompactorStatus : CompactorCommand() {
    override fun execute() {
        val status = compactorService.status(accountBucket())
        if (!status.exists) {
            println("Account compactor: not created (region ${status.region})")
            return
        }
        val state = if (status.desiredCount > 0) "running" else "stopped"
        val task = if (status.taskId.isEmpty()) "none" else "${status.taskId} (${status.taskStatus})"
        println(
            """
            |Account compactor: $state (region ${status.region})
            |Tasks: ${status.runningCount} running, ${status.desiredCount} desired
            |Task: $task
            |Recent log lines:
            |${status.logLines.joinToString("\n").ifEmpty { "(none)" }}
            """.trimMargin(),
        )
    }
}
