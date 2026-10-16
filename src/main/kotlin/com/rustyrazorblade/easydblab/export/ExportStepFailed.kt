package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import java.io.IOException

/** What an export copies, as its failures and its report name it. */
enum class ExportSignal(
    val label: String,
) {
    METRICS("metrics"),
    LOGS("logs"),
    TRACES("traces"),
    ANNOTATIONS("annotations"),
    REPORTS("reports"),

    /** The compose stack, its configuration and the dashboards. */
    STACK("stack"),
}

/**
 * A step of an export failed. Every failure names the signal it was copying and the step, so the
 * operator knows what was being read when it stopped.
 *
 * @property signal what was being copied.
 * @property step what the export was doing, such as `copy block 01J... of acme`.
 */
class ExportStepFailed(
    val signal: ExportSignal,
    val step: String,
    cause: Throwable,
) : RuntimeException("The export failed copying ${signal.label} ($step): ${cause.message}", cause)

/** An object an export listed was gone when it was read. */
class ObjectGoneException(
    val key: String,
) : IOException("$key was listed but is gone")

/** Runs [block] as the [step] of copying [signal]; any failure becomes an [ExportStepFailed] naming both. */
internal inline fun <T> exportStep(
    signal: ExportSignal,
    step: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: Exception) {
        // An inner step's failure already names the step that failed.
        throw e as? ExportStepFailed ?: ExportStepFailed(signal, step, e)
    }

/**
 * Runs [block]; an [ExportStepFailed] is reported as [Event.Export.Failed] with [target]'s path, its
 * signal, step and reason, and the saved [logs] of any temporary stacks, and fails the command.
 */
internal fun <T> EventBus.reportingStepFailure(
    target: ExportTarget,
    logs: () -> List<String> = { emptyList() },
    block: () -> T,
): T =
    try {
        block()
    } catch (e: ExportStepFailed) {
        emit(Event.Export.Failed(target.zip.path, e.signal.label, e.step, e.cause?.message ?: e.message.orEmpty(), logs()))
        throw CommandFailedException(e.message ?: "The export failed")
    }
