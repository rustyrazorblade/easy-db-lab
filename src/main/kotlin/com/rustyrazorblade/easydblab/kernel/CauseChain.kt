package com.rustyrazorblade.easydblab.kernel

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Returns this exception followed by each of its causes, outermost first.
 *
 * A cause chain can loop (`initCause` lets two exceptions name each other), so each exception is
 * listed once and the walk stops at the first repeat. Exceptions are compared by identity, because
 * an exception's `equals` may be overridden.
 *
 * Lives in `kernel/` because the command failure reporter and the provider error handlers both walk
 * cause chains, and neither layer may depend on the other.
 */
fun Throwable.causeChain(): List<Throwable> {
    val seen: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
    return generateSequence(this) { it.cause }.takeWhile { seen.add(it) }.toList()
}
