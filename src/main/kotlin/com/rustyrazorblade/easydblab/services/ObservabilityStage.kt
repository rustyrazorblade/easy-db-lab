package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect

/**
 * A deploy stage's label and the capabilities it needs: what [AbsentCapabilityReport] reads.
 *
 * @property label the stage's name in `up`'s output and in [com.rustyrazorblade.easydblab.events.Event.Grafana.StageSkipped]
 * @property requires every capability the stage needs; empty when it runs everywhere
 */
data class StageRequirement(
    val label: String,
    val requires: Set<NodeCapability> = emptySet(),
)

/**
 * The observability stack's stages, declared once. `up` builds each stage's resources from
 * [declared] ([DefaultObservabilityStackService]) and `init` reads the same list to say what an
 * absent capability disables ([AbsentCapabilityReport]), so the two can never disagree about which
 * stage needs which capability. Declaring a stage builds no manifest.
 */
enum class ObservabilityStage(
    val label: String,
    val requires: Set<NodeCapability> = emptySet(),
) {
    // Privileged and on the host network: it binds the node's OTLP ports and reads host files.
    OTEL_HOST_COLLECTOR("OTel Host Collector", setOf(NodeCapability.PRIVILEGED)),
    KUBE_STATE_METRICS("kube-state-metrics"),

    // Needs a systemd journal to read and a privileged pod to read it.
    FLUENT_BIT_JOURNALD("Fluent Bit Journald", setOf(NodeCapability.SYSTEMD_JOURNAL, NodeCapability.PRIVILEGED)),
    EBPF_EXPORTER("ebpf_exporter", setOf(NodeCapability.PRIVILEGED)),
    REGISTRY("Registry"),
    S3_MANAGER("S3 Manager", setOf(NodeCapability.S3)),
    BEYLA("Beyla", setOf(NodeCapability.PRIVILEGED)),
    YACE("YACE", setOf(NodeCapability.CLOUDWATCH)),
    MIMIR("Mimir"),
    LOKI("Loki"),
    TEMPO("Tempo"),
    PYROSCOPE("Pyroscope"),
    PYROSCOPE_AGENT("Pyroscope Agent", setOf(NodeCapability.PRIVILEGED)),

    // Not a stage of its own: the core collector runs without its pod-log receiver where hostPath
    // volumes are not admitted. Declared so `up` and `init` name it like any skipped stage.
    POD_LOGS("Pod logs", setOf(NodeCapability.HOST_PATH)),
    ;

    /** This stage as [AbsentCapabilityReport] reads it. */
    val requirement: StageRequirement get() = StageRequirement(label, requires)

    companion object {
        private val LOCAL_BACKENDS = listOf(MIMIR, LOKI, TEMPO, PYROSCOPE)

        /**
         * The stages a cluster with [telemetryRedirect] deploys, in deploy order, after the core
         * collector. The host add-on comes first, as it waits for the core's rollout. The local
         * backends exist only in local mode. The Pyroscope eBPF agent runs in both modes, pointed at
         * the external stack under a redirect, and is its own stage so an environment without
         * privileged pods still runs the Pyroscope server. [POD_LOGS] is last and applies nothing.
         */
        fun declared(telemetryRedirect: TelemetryRedirect?): List<ObservabilityStage> {
            val agents =
                listOf(OTEL_HOST_COLLECTOR, KUBE_STATE_METRICS, FLUENT_BIT_JOURNALD, EBPF_EXPORTER, REGISTRY, S3_MANAGER, BEYLA, YACE)
            val backends = if (telemetryRedirect == null) LOCAL_BACKENDS else emptyList()
            return agents + backends + PYROSCOPE_AGENT + POD_LOGS
        }
    }
}
