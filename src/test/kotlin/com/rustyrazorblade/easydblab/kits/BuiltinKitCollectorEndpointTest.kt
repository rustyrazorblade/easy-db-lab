package com.rustyrazorblade.easydblab.kits

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Kits send telemetry to the core collector through its Service (`__OTEL_OTLP_HTTP_ENDPOINT__`,
 * `__OTEL_COLLECTOR_HOST__`), which keeps the traffic on the sender's node. A node IP
 * (`$(HOST_IP)`) or the control node's address names a host listener that exists only where the
 * host add-on runs (`PRIVILEGED`), and the control node's address also sends every node's telemetry
 * to one collector. The backends publish no host port either: kits reach them through the
 * template variables (`__PYROSCOPE_URL__`), which name their Services.
 */
class BuiltinKitCollectorEndpointTest {
    @Test
    fun `no built-in kit sends telemetry or profiles to a node IP or the control node`() {
        val offenders =
            builtinKitFiles().flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    "${file.parentFile.name}/${file.name}:${index + 1}: ${line.trim()}".takeIf { NODE_ENDPOINT.containsMatchIn(line) }
                }
            }

        assertThat(builtinKitFiles().map { it.name }).contains("kit.yaml", "statefulset.yaml.template")
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the scan recognises every node-bound endpoint form`() {
        listOf(
            "value: \"http://\$(HOST_IP):4318\"",
            "endpoint=\"http://'\"\${CONTROL_HOST_PRIVATE}\"':4318/v1/metrics\"",
            "url=\"http://\${CONTROL_HOST_PRIVATE}:4317\"",
            "endpoint: \${env:HOST_IP}:4317",
            "local-agent-host-port = \"otel-collector.default.svc.cluster.local:6831\"",
            "PYROSCOPE_URL=\"http://\${CONTROL_HOST_PRIVATE}:4040\"",
            "url: http://\$(HOST_IP):9009/api/v1/push",
            "curl http://\$CONTROL_HOST_PRIVATE:3100/loki/api/v1/push",
            "endpoint=\"\${CONTROL_HOST_PRIVATE}:4320\"",
            "TEMPO=http://\${HOST_IP}:3200",
        ).forEach { assertThat(NODE_ENDPOINT.containsMatchIn(it)).describedAs(it).isTrue() }
        listOf(
            "value: \"__OTEL_OTLP_HTTP_ENDPOINT__\"",
            "local-agent-host-port = \"__OTEL_COLLECTOR_HOST__:6831\"",
            "curl http://\${CONTROL_HOST_PRIVATE}:30300/management",
            "PYROSCOPE_URL=\"__PYROSCOPE_URL__\"",
            "curl http://\${CONTROL_HOST_PRIVATE}:40400/metrics",
        ).forEach { assertThat(NODE_ENDPOINT.containsMatchIn(it)).describedAs(it).isFalse() }
    }

    /** Every text file of every built-in kit. */
    private fun builtinKitFiles(): List<File> =
        javaClass.classLoader
            .getResources(KITS_RESOURCE_DIR)
            .toList()
            .flatMap { url -> File(url.toURI()).listFiles { dir -> File(dir, "kit.yaml").isFile }.orEmpty().toList() }
            .distinctBy { it.name }
            .flatMap { kitDir -> kitDir.walkTopDown().filter { it.isFile && it.extension in TEXT_EXTENSIONS }.toList() }

    private companion object {
        const val KITS_RESOURCE_DIR = "com/rustyrazorblade/easydblab/kits"
        val TEXT_EXTENSIONS = setOf("yaml", "template", "sh", "md", "alloy", "conf")

        /**
         * A collector port (OTLP 4317/4318, Jaeger 6831) or a backend port (Mimir 9009, Loki 3100, Tempo
         * 3200 and 4320, Pyroscope 4040) on a node IP, the control node, or a
         * hand-written Service name the template variables do not supply.
         */
        val NODE_ENDPOINT =
            Regex("""(HOST_IP\)?\}?|CONTROL_HOST_PRIVATE\}?"?'?|svc\.cluster\.local):(4317|4318|6831|9009|3100|3200|4320|4040)\b""")
    }
}
