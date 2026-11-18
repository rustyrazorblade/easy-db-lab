package com.rustyrazorblade.easydblab.kernel

import com.rustyrazorblade.easydblab.configuration.environment.Environment

/**
 * A command that is about to record an execution environment, rather than run in the one already
 * recorded. `init` is the one such command: in a new directory there is no recorded environment, and
 * in an old one the recorded environment is the one being replaced.
 *
 * `services/CommandExecutor` asks a command for this before deciding whether `@RequireProfileSetup`
 * applies, so `init --local` needs no AWS profile. It is called after Picocli has parsed the options.
 * Lives in `kernel/` because commands implement it and the executor reads it.
 */
fun interface DeclaresEnvironment {
    /** The environment this command will record, from its parsed options. */
    fun declaredEnvironment(): Environment
}
