package com.rustyrazorblade.easydblab.events

/**
 * Stack-based context for tracking the currently executing command name.
 *
 * Commands push their name on entry and pop on exit. When commands call other commands
 * (e.g., Init calls Up), the stack ensures the innermost command name is reported.
 *
 * Uses ThreadLocal to isolate context per thread.
 */
object EventContext {
    private val stack: ThreadLocal<ArrayDeque<String>> = ThreadLocal.withInitial { ArrayDeque() }

    /**
     * Push a command name onto the context stack.
     * Call this at the start of command execution.
     */
    fun push(commandName: String) {
        stack.get().addLast(commandName)
    }

    /**
     * Pop the most recent command name from the context stack.
     * Call this in the finally block of command execution.
     */
    fun pop() {
        val deque = stack.get()
        if (deque.isNotEmpty()) {
            deque.removeLast()
        }
    }

    /**
     * Get the current (innermost) command name, or null if no command is executing.
     */
    fun current(): String? {
        val deque = stack.get()
        return if (deque.isNotEmpty()) deque.last() else null
    }

    /**
     * Clear the context stack for the current thread.
     * Primarily useful for testing.
     */
    fun clear() {
        stack.get().clear()
    }
}
