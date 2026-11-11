package com.rustyrazorblade.easydblab.architecture.fixtures

import software.amazon.awssdk.core.exception.SdkException

/**
 * A deliberate violation for [com.rustyrazorblade.easydblab.architecture.PackageBoundaryTest]: the SDK type
 * appears only in a `catch` clause, which ArchUnit's stock dependency rule does not see.
 */
class SdkOnlyInCatch {
    fun swallow(action: () -> Unit) {
        try {
            action()
        } catch (e: SdkException) {
            println(e.message)
        }
    }
}
