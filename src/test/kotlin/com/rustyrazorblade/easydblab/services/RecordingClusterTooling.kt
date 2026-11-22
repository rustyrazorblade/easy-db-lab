package com.rustyrazorblade.easydblab.services

/**
 * A [ClusterTooling] that records each call and answers it with [respond], so a service test can
 * assert on what the service did with the output it got back. [respond] may throw, to stand for a
 * failed tool.
 */
internal class RecordingClusterTooling(
    private val respond: (Call) -> String = { "" },
) : ClusterTooling {
    /**
     * One call. [args] is the argument list the tool sees, with the input argument as `-`; [input]
     * is null for [ClusterTooling.run].
     */
    data class Call(
        val tool: ClusterTool,
        val args: List<String>,
        val input: String? = null,
        val echo: Boolean = true,
    )

    val calls = mutableListOf<Call>()

    override fun run(
        tool: ClusterTool,
        args: List<String>,
        echo: Boolean,
    ): String = record(Call(tool, args, input = null, echo = echo))

    override fun runWithInput(
        tool: ClusterTool,
        args: List<ToolArg>,
        input: String,
        echo: Boolean,
    ): String = record(Call(tool, args.toToolArguments(), input, echo))

    private fun record(call: Call): String {
        calls += call
        return respond(call)
    }
}
