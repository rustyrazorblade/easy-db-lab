package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.exceptions.EasyDBLabException
import com.rustyrazorblade.easydblab.ssh.redactUrlCredentials
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
 * stdin is piped to the process and closed, so no manifest or values file is written. stdin, stdout
 * and stderr each have a thread of their own, so a full pipe cannot stall a tool that writes a lot
 * before it has read its input. The calling thread only waits for the tool to exit, for at most
 * [timeout]; a tool still running then (a cluster that cannot be reached) is killed with
 * `destroyForcibly` and the call throws [ClusterToolTimedOutException]. An interrupt kills it too.
 * stdout is shown with `println` when `echo` is set, once the tool has finished, as the SSH form
 * does. Both streams have URL credentials redacted, as the SSH client does.
 *
 * This class never touches `socksProxyHost` or `socksProxyPort`: the wrapper routes each call through
 * the recorded tunnel, or straight to the cluster when the workspace records direct mode.
 *
 * @param workspace the cluster workspace, which holds `kubeconfig` and `bin/`
 * @param environment prepares the process environment and writes the wrappers into `bin/`
 * @param inheritedEnvironment the environment each process starts from, before `HELM_*` and
 *   `KUBECONFIG` are cleared; this JVM's own environment unless a test passes another
 * @param timeout how long one call may run before it is killed
 */
class LocalClusterTooling(
    private val workspace: File,
    private val environment: KitProcessEnvironment,
    private val inheritedEnvironment: Map<String, String> = System.getenv(),
    private val timeout: Duration = Constants.ClusterTooling.LOCAL_TOOL_TIMEOUT,
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
        val result = exchange(tool, args, process, stdin)
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

    /**
     * Feeds [stdin] and drains stdout and stderr, each on a thread of its own, while this thread waits
     * for the tool to exit within [timeout]. Waiting here, not reading, is what lets an interrupt or the
     * timeout end the call: a blocked stream read answers neither.
     *
     * @throws ClusterToolTimedOutException when the tool is still running after [timeout]
     */
    private fun exchange(
        tool: ClusterTool,
        args: List<String>,
        process: Process,
        stdin: String?,
    ): Outcome {
        try {
            val stdout = Drain(process.inputStream, "${tool.executable}-stdout")
            val stderr = Drain(process.errorStream, "${tool.executable}-stderr")
            thread(isDaemon = true, name = "${tool.executable}-stdin") { feed(process, stdin) }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw ClusterToolTimedOutException(tool, args, timeout)
            }
            return Outcome(stdout.await(), stderr.await(), process.exitValue())
        } finally {
            // Ends a tool still running after an interrupt, the timeout or a failed read, so its
            // threads see the end of their streams. Does nothing once the process has exited.
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
            // A tool that fails early, or one killed by the timeout, exits without reading its input;
            // its exit status and stderr, or the timeout, say why.
            log.debug(e) { "The tool closed its input before all of stdin was written" }
        }
    }

    /** Reads [stream] to its end on a daemon thread of its own, so a full pipe never stalls the tool. */
    private class Drain(
        stream: InputStream,
        name: String,
    ) {
        private var text = ""
        private var failure: IOException? = null
        private val reader =
            thread(isDaemon = true, name = name) {
                try {
                    text = stream.readBytes().toString(Charsets.UTF_8)
                } catch (e: IOException) {
                    failure = e
                }
            }

        /** What the stream held, once the reader reached its end; `join` makes the reader's writes visible. */
        fun await(): String {
            reader.join()
            failure?.let { throw it }
            return text
        }
    }
}

/**
 * Thrown when the local form of [ClusterTooling] kills a helm or kubectl call that ran longer than
 * [timeout] (`Constants.ClusterTooling.LOCAL_TOOL_TIMEOUT` unless a test sets another). It is a
 * sibling of [ClusterToolFailedException], not one of them: the tool never exited, so there is no
 * exit status or complete output to carry. The message names the tool and its arguments, with URL
 * credentials redacted, and the timeout. [args] is kept unredacted, so never print it directly.
 */
class ClusterToolTimedOutException(
    val tool: ClusterTool,
    val args: List<String>,
    val timeout: Duration,
) : EasyDBLabException(
        redactUrlCredentials((listOf(tool.executable) + args).joinToString(" ")) +
            " did not finish within $timeout and was killed",
    )
