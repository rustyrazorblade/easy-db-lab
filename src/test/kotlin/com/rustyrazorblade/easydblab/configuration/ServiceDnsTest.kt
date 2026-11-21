package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the one place that writes the in-cluster addresses of easy-db-lab's Services, so every
 * producer and reader agrees on the short `<service>.<namespace>.svc` name in the cluster namespace.
 */
class ServiceDnsTest {
    private val dns = ServiceDns(K8sNamespace("lab-a"))

    @Test
    fun `a service host is the short svc name in the cluster namespace`() {
        assertThat(dns.host("mimir")).isEqualTo("mimir.lab-a.svc")
        assertThat(ServiceDns(K8sNamespace("default")).host("loki")).isEqualTo("loki.default.svc")
    }

    @Test
    fun `the backend URLs address their Services in the cluster namespace`() {
        assertThat(dns.mimirPush()).isEqualTo("http://mimir.lab-a.svc:${Constants.K8s.MIMIR_HTTP_PORT}/api/v1/push")
        assertThat(dns.lokiOtlp()).isEqualTo("http://loki.lab-a.svc:${Constants.K8s.LOKI_HTTP_PORT}/otlp")
        assertThat(dns.tempo()).isEqualTo("http://tempo.lab-a.svc:${Constants.K8s.TEMPO_PORT}")
        assertThat(dns.tempoOtlpGrpc()).isEqualTo("tempo.lab-a.svc:${Constants.K8s.TEMPO_OTLP_GRPC_PORT}")
        assertThat(dns.pyroscope()).isEqualTo("http://pyroscope.lab-a.svc:${Constants.K8s.PYROSCOPE_PORT}")
    }

    @Test
    fun `the collector endpoints address its Service on the OTLP ports`() {
        assertThat(dns.collectorHost()).isEqualTo("otel-collector.lab-a.svc")
        assertThat(dns.otlpHttpEndpoint()).isEqualTo("http://otel-collector.lab-a.svc:${Constants.K8s.OTEL_HTTP_PORT}")
        assertThat(dns.otlpGrpcEndpoint()).isEqualTo("http://otel-collector.lab-a.svc:${Constants.K8s.OTEL_GRPC_PORT}")
    }

    @Test
    fun `no address assumes the cluster DNS domain`() {
        val all =
            listOf(dns.mimir(), dns.loki(), dns.tempo(), dns.pyroscope(), dns.otlpHttpEndpoint(), dns.otlpGrpcEndpoint())

        assertThat(all).noneMatch { it.contains("cluster.local") }
    }
}
