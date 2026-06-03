package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.annotations.KitCommand
import io.github.classgraph.ClassGraph
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * A scanned kit command entry discovered via classpath scanning.
 *
 * @property kit The kit name this command belongs to (matches the installed kit directory name).
 * @property name The PicoCLI subcommand name to register under the kit group.
 * @property description The user-facing description, read from the PicoCLI @Command annotation.
 * @property cls The command class to instantiate via PicoCLI + Koin.
 */
data class ScannedKitCommand(
    val kit: String,
    val name: String,
    val description: String,
    val cls: Class<*>,
)

/**
 * Discovers all classes on the classpath annotated with [KitCommand].
 *
 * Results are grouped by kit name and cached after the first scan so that both
 * [CommandLineParser] (dynamic subcommand registration) and [KitInfo] (display)
 * pay the ClassGraph cost only once. Works across fat JARs and external JARs,
 * enabling third-party kit contributors to add Kotlin commands without modifying
 * the core codebase.
 */
interface KitCommandScanner {
    /** Returns all discovered kit commands grouped by kit name. */
    fun findAll(): Map<String, List<ScannedKitCommand>>

    /** Returns commands for a specific kit name, or an empty list if none found. */
    fun forKit(kitName: String): List<ScannedKitCommand> = findAll()[kitName] ?: emptyList()
}

/**
 * Default implementation of [KitCommandScanner] using ClassGraph for classpath scanning.
 *
 * The description for each command is sourced from the first element of the PicoCLI
 * `@Command(description = [...])` annotation — the single source of truth that also
 * drives `--help` output.
 */
class DefaultKitCommandScanner : KitCommandScanner {
    private val log = KotlinLogging.logger {}

    private val cache: Map<String, List<ScannedKitCommand>> by lazy { scan() }

    override fun findAll(): Map<String, List<ScannedKitCommand>> = cache

    @Suppress("TooGenericExceptionCaught")
    private fun scan(): Map<String, List<ScannedKitCommand>> {
        log.debug { "Scanning classpath for @KitCommand-annotated classes" }
        return try {
            ClassGraph()
                .acceptPackages("com.rustyrazorblade.easydblab")
                .enableAnnotationInfo()
                .enableClassInfo()
                .scan()
                .use { result ->
                    result
                        .getClassesWithAnnotation(KitCommand::class.java)
                        .mapNotNull { classInfo ->
                            try {
                                val kitAnn = classInfo.getAnnotationInfo(KitCommand::class.java)
                                val kit = kitAnn.parameterValues.getValue("kit") as String
                                val name = kitAnn.parameterValues.getValue("name") as String

                                val cmdAnn = classInfo.getAnnotationInfo("picocli.CommandLine\$Command")

                                @Suppress("UNCHECKED_CAST")
                                val descArr = cmdAnn?.parameterValues?.getValue("description") as? Array<String>
                                val description = descArr?.firstOrNull() ?: ""

                                ScannedKitCommand(
                                    kit = kit,
                                    name = name,
                                    description = description,
                                    cls = classInfo.loadClass(),
                                )
                            } catch (e: Exception) {
                                log.warn { "Failed to scan @KitCommand class ${classInfo.name}: ${e.message}" }
                                null
                            }
                        }.groupBy { it.kit }
                }
        } catch (e: Exception) {
            log.error(e) { "Classpath scan for @KitCommand failed" }
            emptyMap()
        }
    }
}
