package com.rustyrazorblade.easydblab.kits

import java.io.File

/**
 * Runs a kit shell step the way `WorkloadStepExecutor` does, with a stub `kubectl` first on
 * `PATH`, so a test can check what the step would have applied without a cluster.
 *
 * The stub records each invocation's arguments and the standard input it was given. A call that
 * matches a canned reply (see [reply]) prints that reply and exits with its code; any other call
 * passes its standard input through to standard output, as `kubectl ... -o yaml` would in a
 * pipeline. Canned replies stand in for `kubectl get` (pod status JSON) and `kubectl logs`. A verb
 * given a fixed answer with [respond], or a bash body with [handle], answers every call of that verb
 * before any reply is matched.
 */
class StubKubectl(
    private val dir: File,
) {
    private val log = File(dir, "kubectl.log")
    private val out = File(dir, "script.out")
    private val stdinDir = File(dir, "stdin")
    private val repliesDir = File(dir, "replies")
    private val verbsDir = File(dir, "verbs")
    private var replyCount = 0

    init {
        dir.mkdirs()
        stdinDir.mkdirs()
        repliesDir.mkdirs()
        verbsDir.mkdirs()
        File(dir, "kubectl").apply {
            writeText(stubScript())
            setExecutable(true)
        }
    }

    /**
     * Makes every call whose arguments start with [argsPrefix] print the next of [stdout] and exit
     * with [exitCode]. Once every reply has been served, the last one repeats, so a poll loop sees
     * a state change and then keeps seeing the final state. The first registered match wins.
     */
    fun reply(
        argsPrefix: String,
        vararg stdout: String,
        exitCode: Int = 0,
    ) {
        require(stdout.isNotEmpty()) { "a reply needs at least one output" }
        val id = "%03d".format(replyCount++)
        File(repliesDir, "$id.match").writeText(argsPrefix)
        File(repliesDir, "$id.exit").writeText(exitCode.toString())
        stdout.forEachIndexed { i, text -> File(repliesDir, "$id.out.$i").writeText(text) }
    }

    /** Makes every `kubectl <verb> ...` call print [output] and exit 0. */
    fun respond(
        verb: String,
        output: String,
    ) {
        File(verbsDir, verb).writeText(output)
    }

    /**
     * Makes every `kubectl <verb> ...` call run [body] under bash, with the call's arguments as
     * `$@`; the call exits with the body's status. This models a server whose answer depends on
     * the arguments, where [respond] answers every call the same way.
     */
    fun handle(
        verb: String,
        body: String,
    ) {
        File(verbsDir, "$verb.sh").writeText(body)
    }

    /**
     * Runs [script] under bash with [env] added, returning the exit code; see [output]. As `kit start`
     * does, every step sees the cluster namespace as `NAMESPACE`: [NAMESPACE] unless [env] names one.
     */
    fun run(
        script: String,
        env: Map<String, String>,
    ): Int =
        ProcessBuilder("bash", "-c", script)
            .directory(dir)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectErrorStream(true)
            .redirectOutput(out)
            .also { pb ->
                pb.environment()["NAMESPACE"] = NAMESPACE
                pb.environment().putAll(env)
                pb.environment()["PATH"] = "${dir.absolutePath}:${System.getenv("PATH")}"
            }.start()
            .waitFor()

    /** The last run's combined stdout and stderr. */
    fun output(): String = if (out.isFile) out.readText() else ""

    /** Each recorded `kubectl` invocation's arguments, one per call. */
    fun invocations(): List<String> = if (log.isFile) log.readLines() else emptyList()

    /** The standard input of every call whose arguments start with [argsPrefix], in call order. */
    fun stdinOf(argsPrefix: String): List<String> =
        invocations()
            .withIndex()
            .filter { it.value.startsWith(argsPrefix) }
            .map { File(stdinDir, it.index.toString()).takeIf(File::isFile)?.readText().orEmpty() }

    private fun stubScript(): String =
        """
        #!/bin/bash
        n=0
        [ -f "${log.absolutePath}" ] && n=${'$'}(wc -l < "${log.absolutePath}" | tr -d ' ')
        args="${'$'}*"
        echo "${'$'}args" >> "${log.absolutePath}"
        if [ -f "${verbsDir.absolutePath}/${'$'}1.sh" ]; then bash "${verbsDir.absolutePath}/${'$'}1.sh" "${'$'}@"; exit ${'$'}?; fi
        if [ -f "${verbsDir.absolutePath}/${'$'}1" ]; then cat "${verbsDir.absolutePath}/${'$'}1"; exit 0; fi
        for match in "${repliesDir.absolutePath}"/*.match; do
          [ -f "${'$'}match" ] || continue
          prefix=${'$'}(cat "${'$'}match")
          case "${'$'}args" in
            "${'$'}prefix"*)
              base=${'$'}{match%.match}
              served=0
              [ -f "${'$'}base.served" ] && served=${'$'}(cat "${'$'}base.served")
              reply="${'$'}base.out.${'$'}served"
              if [ -f "${'$'}reply" ]; then
                echo ${'$'}((served + 1)) > "${'$'}base.served"
              else
                reply="${'$'}base.out.${'$'}((served - 1))"
              fi
              cat "${'$'}reply"
              exit "${'$'}(cat "${'$'}base.exit")"
              ;;
          esac
        done
        tee "${stdinDir.absolutePath}/${'$'}n"
        """.trimIndent() + "\n"

    companion object {
        /** The cluster namespace a stubbed step runs in. */
        const val NAMESPACE = "lab-a"
    }
}
