package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants

/**
 * The in-cluster addresses of the observability backends, written once so every in-cluster
 * producer and reader (the collector exporters, Tempo's metrics generator, Grafana's datasources,
 * the Alloy eBPF agent, the sidecar, stress and kit Pyroscope agents) dials the same Service DNS
 * name. No address here is built from a node IP: the backends run on the pod network behind
 * Services, and every in-cluster caller resolves cluster DNS (`ClusterFirst`, or
 * `ClusterFirstWithHostNet` on host networking).
 *
 * The short `.svc` form resolves through a pod's DNS search path and makes no assumption about the
 * cluster DNS domain, as [com.rustyrazorblade.easydblab.configuration.otel.OtelEndpoints] does for
 * the collector.
 */
object BackendEndpoints {
    /** The Service host of [service] in the observability namespace, e.g. `mimir.default.svc`. */
    fun host(service: String): String = "$service.${Constants.K8s.NAMESPACE}.svc"

    /** The Mimir Service host. */
    fun mimirHost(): String = host(Constants.K8s.MIMIR_APP_LABEL)

    /** Pyroscope's ingest base URL, e.g. `http://pyroscope.default.svc:4040`. */
    fun pyroscope(): String = httpUrl(Constants.K8s.PYROSCOPE_APP_LABEL, Constants.K8s.PYROSCOPE_PORT)

    /** Mimir's base URL (remote write and `/prometheus` live under it). */
    fun mimir(): String = httpUrl(Constants.K8s.MIMIR_APP_LABEL, Constants.K8s.MIMIR_HTTP_PORT)

    /** Mimir's Prometheus remote-write URL. */
    fun mimirPush(): String = "${mimir()}/api/v1/push"

    /** Loki's base URL. */
    fun loki(): String = httpUrl(Constants.K8s.LOKI_APP_LABEL, Constants.K8s.LOKI_HTTP_PORT)

    /** Loki's OTLP base URL; the exporter appends `/v1/logs`. */
    fun lokiOtlp(): String = "${loki()}/otlp"

    /** Tempo's query base URL. */
    fun tempo(): String = httpUrl(Constants.K8s.TEMPO_APP_LABEL, Constants.K8s.TEMPO_PORT)

    /** Tempo's OTLP gRPC receiver as a bare `host:port`, as the OTLP exporter takes it. */
    fun tempoOtlpGrpc(): String = "${host(Constants.K8s.TEMPO_APP_LABEL)}:${Constants.K8s.TEMPO_OTLP_GRPC_PORT}"

    private fun httpUrl(
        service: String,
        port: Int,
    ): String = "http://${host(service)}:$port"
}
