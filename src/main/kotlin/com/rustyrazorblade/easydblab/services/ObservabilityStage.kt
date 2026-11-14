package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect

/**
 * A deploy stage's label and the capability it needs, if any: what [AbsentCapabilityReport] reads.
 *
 * @property label the stage's name in `up`'s output and in [com.rustyrazorblade.easydblab.events.Event.Grafana.StageSkipped]
 * @property requires the capability the stage needs, or null when it runs everywhere
 */
data class StageRequirement(
    val label: String,
    val requires: NodeCapability?,
)

/**
 * The observability stack's stages, declared once. `up` builds each stage's resources from
 * [declared] ([DefaultObservabilityStackService]) and `init` reads the same list to say what an
 * absent capability disables ([AbsentCapabilityReport]), so the two can never disagree about which
 * stage needs which capability. Declaring a stage builds no manifest.
 */
enum class ObservabilityStage(
    val label: String,
    val requires: NodeCapability? = null,
) {
    KUBE_STATE_METRICS("kube-state-metrics"),
    FLUENT_BIT_JOURNALD("Fluent Bit Journald", NodeCapability.SYSTEMD_JOURNAL),
    EBPF_EXPORTER("ebpf_exporter"),
    REGISTRY("Registry"),
    S3_MANAGER("S3 Manager", NodeCapability.S3),
    BEYLA("Beyla"),
    YACE("YACE", NodeCapability.CLOUDWATCH),
    MIMIR("Mimir"),
    LOKI("Loki"),
    TEMPO("Tempo"),
    PYROSCOPE("Pyroscope"),
    PYROSCOPE_AGENT("Pyroscope Agent"),
    ;

    /** This stage as [AbsentCapabilityReport] reads it. */
    val requirement: StageRequirement get() = StageRequirement(label, requires)

    companion object {
        private val LOCAL_BACKENDS = listOf(MIMIR, LOKI, TEMPO, PYROSCOPE)

        /**
         * The stages a cluster with [telemetryRedirect] deploys, in deploy order. The local backends
         * exist only in local mode; a redirect cluster runs the Pyroscope eBPF agent alone, pointed at
         * the external stack.
         */
        fun declared(telemetryRedirect: TelemetryRedirect?): List<ObservabilityStage> {
            val agents = listOf(KUBE_STATE_METRICS, FLUENT_BIT_JOURNALD, EBPF_EXPORTER, REGISTRY, S3_MANAGER, BEYLA, YACE)
            return when (telemetryRedirect) {
                null -> agents + LOCAL_BACKENDS
                else -> agents + PYROSCOPE_AGENT
            }
        }
    }
}
