package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.KitRuntime
import com.rustyrazorblade.easydblab.services.sql.KitService
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.client.utils.Serialization
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * The Kubernetes Service each endpoint of an installed kit is published on, and its port there.
 *
 * Kits name their Services freely (ignite3's `ignite-nodeport`, flink's `flink-session-ui`), and a
 * chart or an operator can own one (Strimzi's `kafka-kafka-external-bootstrap`), so no name is
 * guessed from the kit. An endpoint is resolved, in order, to:
 *
 * 1. the Service its `kit.yaml` entry names ([KitEndpoint.service]), in the runtime's namespace;
 * 2. the Service a manifest in the kit's directory declares with the endpoint's port as a NodePort,
 *    at the Service port behind it: the manifests rendered at install time, the files `start` applies;
 * 3. on a Helm kit, the Service named for its release, at an unknown port.
 *
 * Anything else has no known Service.
 *
 * @param runtime the kit's runtime, for its namespace and Helm release
 * @param byNodePort the manifest Service, with its Service port, behind each NodePort
 */
class KitEndpointServices(
    private val runtime: KitRuntime?,
    private val byNodePort: Map<Int, KitService>,
) {
    /** The Service [endpoint] is published on, or null when nothing declares one. */
    fun of(endpoint: KitEndpoint): KitService? {
        val namespace = runtime?.namespace ?: DEFAULT_NAMESPACE
        return when {
            endpoint.service.isNotBlank() -> KitService(namespace, endpoint.service, endpoint.servicePort)
            else ->
                byNodePort[endpoint.port]
                    ?: runtime
                        ?.takeIf { it.type == KitRuntime.RuntimeType.HELM && it.release.isNotBlank() }
                        ?.let { KitService(namespace, it.release, null) }
        }
    }

    companion object {
        private val log = KotlinLogging.logger {}
        private val DOCUMENT_SEPARATOR = Regex("(?m)^---\\s*$")
        private const val DEFAULT_NAMESPACE = "default"
        private val MANIFEST_EXTENSIONS = setOf("yaml", "yml")

        /** The Services of the installed kit in [kitDir], whose descriptor is [config]. */
        fun read(
            kitDir: File,
            config: KitConfig,
        ): KitEndpointServices = KitEndpointServices(config.runtime, manifestServices(kitDir))

        private fun manifestServices(kitDir: File): Map<Int, KitService> =
            kitDir
                .walkTopDown()
                .filter { it.isFile && it.extension in MANIFEST_EXTENSIONS && it.name != Constants.Kit.CONFIG_FILE }
                .flatMap { file -> services(file) }
                .flatMap { service ->
                    val namespace = service.metadata.namespace ?: DEFAULT_NAMESPACE
                    service.spec
                        ?.ports
                        .orEmpty()
                        .mapNotNull { p -> p.nodePort?.let { it to KitService(namespace, service.metadata.name, p.port) } }
                }.toMap()

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
