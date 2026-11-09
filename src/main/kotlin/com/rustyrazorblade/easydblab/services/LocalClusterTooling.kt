package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ssh.redactUrlCredentials
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Removes from [environment] every `HELM_*` variable and `KUBECONFIG`, so a value inherited from the
 * operator's shell (`HELM_REPOSITORY_CONFIG`, `HELM_KUBECONTEXT`, a `KUBECONFIG` of their own) cannot
 * redirect a call away from the workspace. Variables with any other name are left alone.
 */
internal fun clearInheritedToolEnvironment(environment: MutableMap<String, String>) {
    environment.keys.removeIf {
        it.startsWith(Constants.ClusterTooling.HELM_ENV_PREFIX) || it == Constants.ClusterTooling.KUBECONFIG_ENV
    }
}

/**
 * [ClusterTooling] for a cluster with no SSH host (a local K3s in Docker): runs helm and kubectl as
 * processes on this machine, in the environment kit shell steps get.
 *
 * The tool is started by the absolute path of its workspace wrapper, `<workspace>/bin/<tool>`, and
 * never by bare name: `ProcessBuilder` resolves a bare name against the parent JVM's `PATH`, not the
 * `PATH` [KitProcessEnvironment] sets for the child, so a bare `kubectl` would skip the wrapper and
 * with it the workspace kubeconfig and the proxy rules. Arguments go as a list, never through a shell,
 * so a value needs no quoting and [ClusterTooling.run]'s `shellQuoted` quotes nothing here.
 *
 * helm's config, cache and data directories sit under `<workspace>/.helm/`, so `helm repo add` records
 * its repository in the workspace and not in the operator's home. `HELM_*` and `KUBECONFIG` inherited
 * from the operator's shell are cleared first, so nothing but the workspace decides which cluster and
 * which repositories a call sees.
 *
 * stdin is piped to the process and closed, so no manifest or values file is written. stdout and
 * stderr are read at the same time, so a full pipe cannot stall a tool that writes a lot before it
 * has read its input. stdout is shown with `println` when `echo` is set, once the tool has finished,
 * as the SSH form does. Both streams have URL credentials redacted, as the SSH client does.
 *
 * This class never touches `socksProxyHost` or `socksProxyPort`: the wrapper routes each call through
 * the recorded tunnel, or straight to the cluster when the workspace records direct mode.
 *
 * @param workspace the cluster workspace, which holds `kubeconfig` and `bin/`
 * @param environment prepares the process environment and writes the wrappers into `bin/`
 * @param inheritedEnvironment the environment each process starts from, before `HELM_*` and
 *   `KUBECONFIG` are cleared; this JVM's own environment unless a test passes another
 */
class LocalClusterTooling(
    private val workspace: File,
    private val environment: KitProcessEnvironment,
    private val inheritedEnvironment: Map<String, String> = System.getenv(),
) : ClusterTooling {
    private val log = KotlinLogging.logger {}

    override fun run(
        tool: ClusterTool,
        args: List<String>,
        stdin: String?,
        echo: Boolean,
        shellQuoted: Set<Int>,
    ): String {
        requireOneStdinArgument(tool, args, stdin)
        requireShellQuotedWithin(tool, args, shellQuoted)
        val process = start(tool, args)
        val result = exchange(process, stdin)
        if (result.exitStatus != 0) {
            throw ClusterToolFailedException(
                tool = tool,
                args = args,
                exitStatus = result.exitStatus,
                stderr = result.stderr,
                stdout = result.stdout,
            )
        }
        val stdout = redactUrlCredentials(result.stdout)
        if (echo && stdout.isNotBlank()) println(stdout.trimEnd())
        return stdout
    }

    /** What a finished process wrote and how it ended. */
    private class Outcome(
        val stdout: String,
        val stderr: String,
        val exitStatus: Int,
    )

    private fun start(
        tool: ClusterTool,
        args: List<String>,
    ): Process {
        val wrapper = File(File(workspace, Constants.ToolWrappers.DIRECTORY), tool.executable).absolutePath
        val builder = ProcessBuilder(listOf(wrapper) + args).directory(workspace)
        builder.environment().run {
            clear()
            putAll(inheritedEnvironment)
            clearInheritedToolEnvironment(this)
        }
        return environment.applyTo(builder, workspace, helmHomes()).start()
    }

    private fun helmHomes(): Map<String, String> {
        val root = File(workspace, Constants.ClusterTooling.HELM_HOME_DIRECTORY)
        return Constants.ClusterTooling.HELM_HOMES.mapValues { (_, directory) -> File(root, directory).absolutePath }
    }

    private fun exchange(
        process: Process,
        stdin: String?,
    ): Outcome =
        Executors.newFixedThreadPool(STREAMS).use { pool ->
            try {
                val stdout = pool.submit<String> { process.inputStream.readBytes().toString(Charsets.UTF_8) }
                val stderr = pool.submit<String> { process.errorStream.readBytes().toString(Charsets.UTF_8) }
                feed(process, stdin)
                val exitStatus = process.waitFor()
                Outcome(stdout.get(), stderr.get(), exitStatus)
            } finally {
                // Ends a tool still running after an interrupt or a failed read, so the readers return
                // and closing the pool does not wait on them. Does nothing once the process has exited.
                process.destroyForcibly()
            }
        }

    /** Writes [stdin] to the process and closes its input, so a tool that reads to the end sees the end. */
    private fun feed(
        process: Process,
        stdin: String?,
    ) {
        try {
            process.outputStream.use { if (stdin != null) it.write(stdin.toByteArray(Charsets.UTF_8)) }
        } catch (e: IOException) {
            // A tool that fails early exits without reading its input; its exit status and stderr say why.
            log.debug(e) { "The tool closed its input before all of stdin was written" }
        }
    }

    private companion object {
        /** One reader thread each for stdout and stderr. */
        const val STREAMS = 2
    }
}
