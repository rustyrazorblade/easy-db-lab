package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.sql.KitService
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.client.utils.Serialization
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * The Service port behind each NodePort of the Services an installed kit's manifests declare.
 *
 * A kit endpoint names its NodePort, but `kubectl port-forward svc/...` and a Service DNS name
 * both take the Service port (clickhouse's 30123 is Service port 8123). `status` cannot ask the
 * cluster without a forward of its own, so it reads the mapping from the manifests rendered into
 * the kit's workspace directory at install time, the same files `start` applies.
 *
 * @param ports the Service port for each Service and NodePort
 */
class KitServicePorts(
    private val ports: Map<Pair<KitService, Int>, Int>,
) {
    /** The Service port of [service] behind [nodePort], or [nodePort] when no manifest maps it. */
    fun servicePort(
        service: KitService,
        nodePort: Int,
    ): Int = ports[service to nodePort] ?: nodePort

    companion object {
        private val log = KotlinLogging.logger {}
        private val DOCUMENT_SEPARATOR = Regex("(?m)^---\\s*$")
        private const val DEFAULT_NAMESPACE = "default"

        /** The ports the Service manifests in [kitDir] declare; a file that is not a manifest is skipped. */
        fun read(kitDir: File): KitServicePorts =
            KitServicePorts(
                kitDir
                    .walkTopDown()
                    .filter { it.isFile && it.extension in setOf("yaml", "yml") && it.name != Constants.Kit.CONFIG_FILE }
                    .flatMap { file -> services(file) }
                    .flatMap { service ->
                        val kitService =
                            KitService(service.metadata?.namespace ?: DEFAULT_NAMESPACE, service.metadata.name)
                        service.spec
                            ?.ports
                            .orEmpty()
                            .mapNotNull { p -> p.nodePort?.let { (kitService to it) to p.port } }
                    }.toMap(),
            )

        private fun services(file: File): List<Service> =
            file
                .readText()
                .split(DOCUMENT_SEPARATOR)
                .filter { it.contains("kind: Service") }
                .mapNotNull { document ->
                    runCatching { Serialization.unmarshal(document, Service::class.java) }
                        .onFailure { log.debug(it) { "Skipping a document of ${file.name} that is not a Service" } }
                        .getOrNull()
                        ?.takeIf { it.kind == "Service" && it.metadata?.name != null }
                }
    }
}
