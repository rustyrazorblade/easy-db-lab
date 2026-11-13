package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.Constants

/**
 * The in-cluster addresses of the core OTel collector, written once so every in-cluster producer
 * dials the same name. The core runs behind a Service with `internalTrafficPolicy: Local`, so a
 * producer that resolves the Service reaches its own node's core pod.
 *
 * The short `.svc` form (not `.svc.cluster.local`) resolves through a pod's DNS search path and
 * makes no assumption about the cluster DNS domain.
 */
object OtelEndpoints {
    /** The core collector Service host in [namespace], e.g. `otel-collector.default.svc`. */
    fun serviceHost(namespace: String = Constants.K8s.NAMESPACE): String = "${Constants.K8s.OTEL_COLLECTOR_APP_LABEL}.$namespace.svc"

    /** The OTLP HTTP endpoint a producer posts to, e.g. `http://otel-collector.default.svc:4318`. */
    fun otlpHttpEndpoint(namespace: String = Constants.K8s.NAMESPACE): String =
        "http://${serviceHost(namespace)}:${Constants.K8s.OTEL_HTTP_PORT}"

    /** The OTLP gRPC endpoint a producer exports to, e.g. `http://otel-collector.default.svc:4317`. */
    fun otlpGrpcEndpoint(namespace: String = Constants.K8s.NAMESPACE): String =
        "http://${serviceHost(namespace)}:${Constants.K8s.OTEL_GRPC_PORT}"
}
