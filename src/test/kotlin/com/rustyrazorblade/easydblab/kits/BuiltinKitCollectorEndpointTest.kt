package com.rustyrazorblade.easydblab.kits

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Kits send telemetry to the core collector through its Service (`__OTEL_OTLP_HTTP_ENDPOINT__`,
 * `__OTEL_COLLECTOR_HOST__`), which keeps the traffic on the sender's node. A node IP
 * (`$(HOST_IP)`) or the control node's address names a host listener that exists only where the
 * host add-on runs (`PRIVILEGED`), and the control node's address also sends every node's telemetry
 * to one collector.
 */
class BuiltinKitCollectorEndpointTest {
    @Test
    fun `no built-in kit sends telemetry to a node IP or the control node`() {
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
        ).forEach { assertThat(NODE_ENDPOINT.containsMatchIn(it)).describedAs(it).isTrue() }
        listOf(
            "value: \"__OTEL_OTLP_HTTP_ENDPOINT__\"",
            "local-agent-host-port = \"__OTEL_COLLECTOR_HOST__:6831\"",
            "curl http://\${CONTROL_HOST_PRIVATE}:30300/management",
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
         * A collector port (OTLP 4317/4318, Jaeger 6831) on a node IP, the control node, or a
         * hand-written Service name the template variables do not supply.
         */
        val NODE_ENDPOINT =
            Regex("""(HOST_IP\)?\}?|CONTROL_HOST_PRIVATE\}?"?'?|svc\.cluster\.local):(4317|4318|6831)\b""")
    }
}
