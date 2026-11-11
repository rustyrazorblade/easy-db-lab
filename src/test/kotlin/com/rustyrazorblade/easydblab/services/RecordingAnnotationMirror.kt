package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost

/**
 * An [AnnotationMirror] that records what it is asked to mirror, and fails every push once
 * [failure] is set, for testing the code that mirrors without a Loki.
 */
class RecordingAnnotationMirror(
    var failure: Throwable? = null,
) : AnnotationMirror {
    val pushed = mutableListOf<MirroredAnnotation>()
    val synced = mutableListOf<ClusterHost>()

    override fun push(annotation: MirroredAnnotation): Result<Unit> =
        failure?.let { Result.failure(it) } ?: Result.success(Unit).also { pushed.add(annotation) }

    override fun syncAll(controlHost: ClusterHost): Result<Int> =
        failure?.let { Result.failure(it) } ?: Result.success(0).also { synced.add(controlHost) }
}
