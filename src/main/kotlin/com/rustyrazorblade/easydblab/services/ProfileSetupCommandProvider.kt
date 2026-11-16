package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.kernel.PicoCommand

/**
 * Supplies the interactive profile setup command to [DefaultCommandExecutor].
 *
 * `checkRequirements()` must run profile setup when a command carries `@RequireProfileSetup` and
 * no profile exists. Naming the concrete command class there would make the `services` package
 * depend on the `commands` package, which already depends on `services` — a cycle. This provider
 * is the abstraction that breaks it: `services` owns the interface, and the DI module in `di`
 * binds it to the concrete command.
 *
 * A provider rather than a runner: a runner would call back into [CommandExecutor.execute],
 * which creates a construction cycle in Koin. Producing the command and letting the executor run
 * it keeps the wiring one-directional.
 */
fun interface ProfileSetupCommandProvider {
    /** Creates a fresh profile setup command instance. */
    fun create(): PicoCommand
}
