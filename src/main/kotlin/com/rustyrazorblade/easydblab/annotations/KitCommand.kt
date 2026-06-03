package com.rustyrazorblade.easydblab.annotations

/**
 * Marks a command class as belonging to a specific installed kit.
 *
 * When a kit directory is found in the cluster workspace, any command on the
 * classpath annotated with a matching [kit] name is injected as a subcommand of
 * that kit's dynamic command group. This allows Kotlin commands to appear
 * alongside shell-script lifecycle commands (start, stop, etc.) without requiring
 * a static parent command registration.
 *
 * The subcommand name in the CLI is taken from [name]. The description shown in
 * `kit info` is read from the PicoCLI `@Command(description = [...])` annotation
 * on the same class — no duplication needed.
 *
 * Example:
 * ```kotlin
 * @KitCommand(kit = "presto", name = "sql")
 * @Command(name = "sql", description = ["Execute SQL against the Presto cluster"])
 * class PrestoSql : PicoBaseCommand() { ... }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class KitCommand(
    /** Name of the installed kit directory this command belongs to (e.g. "presto"). */
    val kit: String,
    /** PicoCLI subcommand name to register under the kit group (e.g. "sql"). */
    val name: String,
)
