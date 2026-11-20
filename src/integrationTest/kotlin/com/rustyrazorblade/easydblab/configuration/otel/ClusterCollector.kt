package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import java.time.Duration

/**
 * The cluster's OTel collector as a container: the pinned image running the configuration `up`
 * renders, unedited, so a test exercises exactly what a control node runs.
 *
 * The collector gets a placeholder service account token so its Kubernetes components start; they
 * find no API server, which only means no pod metadata. A test adds how the in-cluster service
 * names resolve (extra hosts or a network with aliases) and starts the container.
 */
internal object ClusterCollector {
    private const val SA_DIR = "/var/run/secrets/kubernetes.io/serviceaccount"
    private const val FAILURE_LINES = 20
    private const val COMPONENT_ID_LENGTH = 30

    /** The control node's collector with [config], stamping [cluster] and writing as [tenant]. Not started. */
    fun container(
        config: String,
        hostName: String,
        cluster: String,
        tenant: String,
    ): GenericContainer<*> =
        GenericContainer("otel/opentelemetry-collector-contrib:${Constants.OtelCollector.VERSION}")
            .withCopyToContainer(Transferable.of(config), "/etc/otel-collector-config.yaml")
            .withCopyToContainer(Transferable.of("placeholder"), "$SA_DIR/token")
            .withCopyToContainer(Transferable.of("default"), "$SA_DIR/namespace")
            // kubelet_stats loads the in-cluster CA when it starts.
            .withCopyToContainer(Transferable.of(ServiceAccountCa.pem()), "$SA_DIR/ca.crt")
            .withCreateContainerCmdModifier { it.withHostName(hostName) }
            // Node identity comes from the downward API on a cluster, never the OS hostname.
            .withEnv("K8S_NODE_NAME", hostName)
            .withEnv("K8S_NODE_IP", "127.0.0.1")
            .withEnv("CLUSTER_NAME", cluster)
            .withEnv("TENANT", tenant)
            .withEnv("KUBERNETES_SERVICE_HOST", "127.0.0.1")
            .withEnv("KUBERNETES_SERVICE_PORT", "1")
            .withCommand("--config=/etc/otel-collector-config.yaml")
            .withExposedPorts(Constants.K8s.OTEL_HTTP_PORT, Constants.K8s.OTEL_HEALTH_PORT)
            .waitingFor(Wait.forHttp("/").forPort(Constants.K8s.OTEL_HEALTH_PORT).withStartupTimeout(Duration.ofMinutes(2)))

    /** The collector's last export failures and drops, one per line, for a failed assertion's message. */
    fun exportFailures(collector: GenericContainer<*>): String =
        collector.logs
            .lines()
            .filter { it.contains("Exporting failed") || it.contains("dropped_items") }
            .map {
                it.substringAfter("\"otelcol.component.id\": ").take(COMPONENT_ID_LENGTH) + " " +
                    it.substringAfter("\"error\": ")
            }.takeLast(FAILURE_LINES)
            .joinToString("\n")

    /** The `data.result` array of a Prometheus or Loki query response. */
    fun results(json: String): List<JsonObject> =
        Json
            .parseToJsonElement(json)
            .jsonObject
            .getValue("data")
            .jsonObject
            .getValue("result")
            .jsonArray
            .map { it.jsonObject }
}
