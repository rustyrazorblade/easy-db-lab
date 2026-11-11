package com.rustyrazorblade.easydblab.profiling

/** `jfrconv` arguments selecting the output format, which easy-db-lab supplies. */
private val RESERVED_JFRCONV_FLAGS = setOf("-o", "--output")

/**
 * Rejects `jfrconv` passthrough arguments that collide with the input chunks and output destination
 * easy-db-lab supplies.
 *
 * `jfrconv`'s grammar makes this far simpler than async-profiler's: inputs and output are
 * *positional* and `-o`/`--output` selects the format. So the whole rule is "no positional
 * arguments, and no `-o`/`--output`" — no enumeration of the option surface, and everything else
 * (`--threads`, `--total`, `--from`, future flags) passes through untouched.
 *
 * As in [AsprofArgValidator], "positional" means a token in flag position: the preceding token does
 * not start with `-`. That keeps a flag's own value (`--include org.apache.cassandra`) from being
 * misread as a positional without having to know which flags consume values.
 */
class JfrconvArgValidator {
    /**
     * @return the first offending argument, or null if every argument is acceptable.
     */
    fun validate(args: List<String>): String? = args.withIndex().firstOrNull { (i, arg) -> isReserved(arg, args, i) }?.value

    /**
     * Renders the operator-facing explanation for a rejected argument.
     */
    fun rejectionMessage(argument: String): String =
        "Refusing to convert: '$argument' is reserved by easy-db-lab.\n" +
            "The tool supplies jfrconv's input chunks and output destination. Use --last to choose\n" +
            "how many chunks to convert and --format to choose the output format."

    private fun isReserved(
        arg: String,
        args: List<String>,
        index: Int,
    ): Boolean {
        if (arg in RESERVED_JFRCONV_FLAGS) return true
        if (arg.substringBefore('=') in RESERVED_JFRCONV_FLAGS) return true
        val inFlagPosition = index == 0 || !args[index - 1].startsWith("-")
        return !arg.startsWith("-") && inFlagPosition
    }
}
