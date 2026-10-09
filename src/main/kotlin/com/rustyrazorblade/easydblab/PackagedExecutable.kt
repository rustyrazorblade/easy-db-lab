package com.rustyrazorblade.easydblab

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A script packaged in the distribution that the CLI writes to disk as an executable file.
 *
 * Several scripts run outside the JVM, such as the `edl-ssm-proxy` wrapper and the workspace tool
 * wrappers. They come from classpath resources, so they work from an installed distribution with no
 * source checkout. Another process can run one of them at any moment, so it must never see a
 * half-written or non-executable file. Each write therefore stages the content in a unique temporary
 * file in the target's own directory, makes that file executable, and renames it over the target.
 *
 * @param content the script text
 * @param makeExecutable marks the staged file executable and reports whether that worked
 */
class PackagedExecutable(
    private val content: String,
    private val makeExecutable: (File) -> Boolean = { it.setExecutable(true, true) },
) {
    /** True when [target] is an executable file that holds this script. */
    fun isWrittenTo(target: File): Boolean = target.isFile && target.canExecute() && target.readText() == content

    /**
     * Writes this script to [target], unless [target] already holds it and is executable.
     *
     * @return true when the file was written, false when it was already up to date
     * @throws IllegalStateException if the staged file cannot be made executable
     */
    fun writeTo(target: File): Boolean {
        if (isWrittenTo(target)) return false
        val directory = target.absoluteFile.parentFile
        directory.mkdirs()
        val staged = Files.createTempFile(directory.toPath(), ".${target.name}.", ".tmp").toFile()
        try {
            staged.writeText(content)
            check(makeExecutable(staged)) { "Could not make ${staged.path} executable" }
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staged.delete()
        }
        return true
    }

    companion object {
        /**
         * The script packaged at the classpath [resource].
         *
         * @throws IllegalArgumentException if the resource is missing from the distribution
         */
        fun fromResource(resource: String): PackagedExecutable =
            PackagedExecutable(
                requireNotNull(PackagedExecutable::class.java.getResource(resource)) { "Missing packaged resource $resource" }.readText(),
            )
    }
}
