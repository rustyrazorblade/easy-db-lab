package com.rustyrazorblade.easydblab.shell

/**
 * Shell-quotes a string to prevent injection when embedding in a remote command.
 * Safe characters pass through unquoted; anything else is wrapped in single quotes
 * with embedded single quotes escaped as '\''.
 */
fun String.shellQuote(): String {
    if (isEmpty()) return "''"
    if (matches(Regex("[a-zA-Z0-9_./:=@%+,-]+"))) return this
    return "'" + replace("'", "'\\''") + "'"
}
