package com.rustyrazorblade.easydblab.kernel

import com.rustyrazorblade.easydblab.annotations.PostExecute
import com.rustyrazorblade.easydblab.annotations.PreExecute
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Callable
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.isAccessible

/**
 * PicoCLI command interface that provides lifecycle hook support.
 *
 * Implements Callable<Int> for PicoCLI compatibility.
 * Executes methods annotated with @PreExecute before the main execute() method,
 * and methods annotated with @PostExecute afterward.
 */
interface PicoCommand : Callable<Int> {
    /**
     * Executes the command with full lifecycle support.
     *
     * Execution order:
     * 1. All @PreExecute methods (sorted by name)
     * 2. The execute() method
     * 3. All @PostExecute methods (sorted by name)
     *
     * @return 0 on success, non-zero on failure
     */
    override fun call(): Int {
        val kClass = this::class

        val preExecuteMethods =
            kClass.declaredMemberFunctions
                .filter { it.findAnnotation<PreExecute>() != null }
                .sortedBy { it.name }

        for (method in preExecuteMethods) {
            method.isAccessible = true
            try {
                method.call(this)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        execute()

        val postExecuteMethods =
            kClass.declaredMemberFunctions
                .filter { it.findAnnotation<PostExecute>() != null }
                .sortedBy { it.name }

        for (method in postExecuteMethods) {
            method.isAccessible = true
            try {
                method.call(this)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        return 0
    }

    /**
     * Implements the main command logic.
     * Subclasses must override this method to provide command functionality.
     */
    fun execute()
}
