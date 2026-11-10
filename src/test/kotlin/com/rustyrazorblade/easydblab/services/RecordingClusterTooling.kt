package com.rustyrazorblade.easydblab.services

/**
 * A [ClusterTooling] that records each call and answers it with [respond], so a service test can
 * assert on the argument lists the service built and on what it did with the output it got back.
 * [respond] may throw, to stand for a failed tool.
 */
internal class RecordingClusterTooling(
    private val respond: (Call) -> String = { "" },
) : ClusterTooling {
    /** One [ClusterTooling.run] call, with the defaults [ClusterTooling.run] has. */
    data class Call(
        val tool: ClusterTool,
        val args: List<String>,
        val stdin: String? = null,
        val echo: Boolean = true,
        val shellQuoted: Set<Int> = emptySet(),
    )

    val calls = mutableListOf<Call>()

    override fun run(
        tool: ClusterTool,
        args: List<String>,
        stdin: String?,
        echo: Boolean,
        shellQuoted: Set<Int>,
    ): String {
        val call = Call(tool, args, stdin, echo, shellQuoted)
        calls += call
        return respond(call)
    }
}
