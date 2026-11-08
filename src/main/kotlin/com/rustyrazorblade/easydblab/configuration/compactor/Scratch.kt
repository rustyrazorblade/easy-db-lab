package com.rustyrazorblade.easydblab.configuration.compactor

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder

/**
 * The compactors' scratch directories on the control node's data mount, as the Fargate task's
 * ephemeral storage was. The lease agent wipes them on acquire; `CompactorAgentInstaller` creates
 * them and gives each to the user its compactor runs as. Tempo's scheduler and worker share one.
 *
 * @property dir the directory's name under [Constants.Compactor.HOST_ROOT] and the agent's scratch root.
 * @property owner the uid that must own it: the image's own user (Mimir's image runs as root).
 * @property mountPath where its compactor keeps its working data in the container.
 */
enum class Scratch(
    val dir: String,
    val owner: Long,
    val mountPath: String,
) {
    MIMIR("mimir", 0L, "/data"),
    LOKI("loki", LokiManifestBuilder.LOKI_UID, "/loki"),
    TEMPO("tempo", TempoManifestBuilder.TEMPO_UID, "/var/tempo"),
    ;

    /** The pod volume that mounts it. */
    val volume: String get() = "$dir-scratch"

    /** Where it is on the control node. */
    val hostPath: String get() = "${Constants.Compactor.HOST_ROOT}/$dir"
}
