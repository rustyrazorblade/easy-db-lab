package com.rustyrazorblade.easydblab.compactorlease

/** Runs the conditional-write contract against the in-memory adapter. */
class InMemoryConditionalObjectsTest : ConditionalObjectsContract() {
    private val store = InMemoryConditionalObjects()

    override fun store(): ConditionalObjects = store
}
