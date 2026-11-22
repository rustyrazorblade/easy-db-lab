package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace

/**
 * The in-cluster addresses of easy-db-lab's Services in the cluster [namespace], written once so
 * every in-cluster producer and reader (the collector exporters, Tempo's metrics generator,
 * Grafana's datasources, the Alloy eBPF agent, the sidecar, stress and kit agents) dials the same
 * name. No address here is built from a node IP: the backends and the core collector run on the
 * pod network behind Services, and every in-cluster caller resolves cluster DNS (`ClusterFirst`,
 * or `ClusterFirstWithHostNet` on host networking). The core collector's Service has
 * `internalTrafficPolicy: Local`, so a producer that resolves it reaches its own node's core pod.
 *
 * The short `.svc` form (not `.svc.cluster.local`) resolves through a pod's DNS search path and
 * makes no assumption about the cluster DNS domain.
 */
class ServiceDns(
    private val namespace: K8sNamespace,
) {
    /** The Service host of [service] in the cluster namespace, e.g. `mimir.<namespace>.svc`. */
    fun host(service: String): String = "$service.${namespace.value}.svc"

    /** The Mimir Service host. */
    fun mimirHost(): String = host(Constants.K8s.MIMIR_APP_LABEL)

    /** Pyroscope's ingest base URL, e.g. `http://pyroscope.<namespace>.svc:4040`. */
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

    /** The core collector Service host, e.g. `otel-collector.<namespace>.svc`. */
    fun collectorHost(): String = host(Constants.K8s.OTEL_COLLECTOR_APP_LABEL)

    /** The OTLP HTTP endpoint a producer posts to, e.g. `http://otel-collector.<namespace>.svc:4318`. */
    fun otlpHttpEndpoint(): String = "http://${collectorHost()}:${Constants.K8s.OTEL_HTTP_PORT}"

    /** The OTLP gRPC endpoint a producer exports to, e.g. `http://otel-collector.<namespace>.svc:4317`. */
    fun otlpGrpcEndpoint(): String = "http://${collectorHost()}:${Constants.K8s.OTEL_GRPC_PORT}"

    /** The core collector's OTLP gRPC receiver as a bare `host:port`. */
    fun collectorGrpcHostPort(): String = "${collectorHost()}:${Constants.K8s.OTEL_GRPC_PORT}"

    private fun httpUrl(
        service: String,
        port: Int,
    ): String = "http://${host(service)}:$port"
}
