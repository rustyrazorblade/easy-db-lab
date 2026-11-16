package com.rustyrazorblade.easydblab.kernel

/**
 * Thrown by a command that has already reported its failure to the user through a typed event, so
 * that the command exits non-zero without the failure being printed a second time.
 *
 * A script or a test plan reads the exit code, not the text. A command that prints "Error: ..."
 * and returns normally exits 0, and the failure goes unseen. `services/CommandExecutor` catches
 * this exception, returns the error exit code, and emits no `Command.ExecutionError` for it,
 * because the command's own event already told the user what went wrong.
 *
 * Lives in `kernel/` because commands throw it and the executor in `services/` handles it.
 */
class CommandFailedException(
    message: String,
) : RuntimeException(message)
