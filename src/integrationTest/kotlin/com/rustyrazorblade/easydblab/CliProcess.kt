package com.rustyrazorblade.easydblab

import java.io.File

/**
 * Runs the real CLI `main` in a new JVM, from the test's own classpath, so a test can see what
 * only a whole process shows: its exit status, and how it answers a signal.
 */
internal object CliProcess {
    private const val MAIN_CLASS = "com.rustyrazorblade.easydblab.MainKt"

    /**
     * Starts the CLI with [args] in [workingDirectory], with [environment] added to this JVM's
     * environment.  Its standard error is merged into its standard output, written to [output].
     */
    fun start(
        workingDirectory: File,
        output: File,
        environment: Map<String, String>,
        vararg args: String,
    ): Process {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val command = listOf(java, "-cp", System.getProperty("java.class.path"), MAIN_CLASS) + args
        return ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .redirectOutput(output)
            .also { it.environment().putAll(environment) }
            .start()
    }
}
