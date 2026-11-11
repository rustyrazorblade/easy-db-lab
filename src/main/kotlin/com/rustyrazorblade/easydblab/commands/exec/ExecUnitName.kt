package com.rustyrazorblade.easydblab.commands.exec

/** The prefix of every systemd unit `exec run` starts, which `exec list` and `exec stop` match on. */
internal const val EXEC_UNIT_PREFIX = "edl-exec-"

private val UNIT_NAME_DISALLOWED = Regex("[^A-Za-z0-9:_.-]")

/**
 * The systemd unit name for an `exec` tool called [name]: the prefix, then [name] with every
 * character systemd does not allow in a unit name replaced by `-`.
 *
 * The result is also safe to put unquoted on a shell command line. `exec run` and `exec stop`
 * both build the name here, so a tool started under a name with a space in it stops under the
 * same name.
 */
internal fun execUnitName(name: String): String = EXEC_UNIT_PREFIX + name.replace(UNIT_NAME_DISALLOWED, "-")
