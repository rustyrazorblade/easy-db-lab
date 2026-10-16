package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorTaskDefinition
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaServerSettings

/**
 * A slice of a local stack: some compose services and the files they mount, by path relative to the
 * stack directory. [LocalStackComposer] joins the slices into one stack.
 *
 * @property services the compose services, by name.
 * @property files the files the services mount, by relative path.
 */
data class ComposePart(
    val services: Map<String, ComposeService>,
    val files: Map<String, String>,
)

/**
 * Turns a [LocalStackSpec] into every file of a local observability stack, by path relative to the
 * stack directory: `compose.yml` and the configuration files its services mount. Every file comes
 * from resources packaged in the distribution; nothing is read from a source checkout or the network.
 *
 * The stack is three parts, each built by its own class: the read-only access to the store
 * ([StoreAccessPart]: the gate, the signer and the store check), the backends ([BackendsPart]:
 * Mimir with its memcached, Loki and Tempo) and the browser's view ([GrafanaViewPart]: Grafana and
 * the documents web server). Writing the files is [LocalStackWriter]'s job.
 */
object LocalStackComposer {
    /** Every file of the stack described by [spec], by path relative to the stack directory. */
    fun compose(spec: LocalStackSpec): Map<String, ByteArray> {
        val parts =
            when (val mode = spec.mode) {
                is LocalStackSpec.Mode.Live ->
                    listOf(
                        StoreAccessPart.live(mode),
                        BackendsPart.live(mode),
                        GrafanaViewPart.compose(
                            spec,
                            GrafanaViewPart.Documents.FromStore(mode.bucket),
                            GrafanaServerSettings.DOWNLOADED_PLUGINS,
                        ),
                    )
                // No gate, signer or store check: the data is on disk. Grafana downloads no plugin,
                // since the stack may have no network.
                is LocalStackSpec.Mode.Offline ->
                    listOf(
                        BackendsPart.offline(mode),
                        GrafanaViewPart.compose(spec, GrafanaViewPart.Documents.FromDisk(mode.dataDir), plugins = emptyList()),
                    )
            }
        val services = parts.flatMap { it.services.entries }.associate { it.toPair() }
        val files = parts.flatMap { it.files.entries }.associate { it.toPair() }
        require(services.size == parts.sumOf { it.services.size }) { "Two parts define the same service" }
        val compose = ComposeFile(services).toYaml()
        return (files + (Constants.LocalStack.COMPOSE_FILE to compose)).mapValues { it.value.toByteArray() }
    }

    /** A packaged resource of this package, as text. */
    internal fun resource(name: String): String = resourceNextTo(LocalStackComposer::class.java, name)

    /** A packaged resource next to [anchor], such as a cluster backend's own configuration file, as text. */
    internal fun resourceNextTo(
        anchor: Class<*>,
        name: String,
    ): String = CompactorTaskDefinition.resource(anchor, name)

    /** The relative path of a configuration file the stack mounts. */
    internal fun configPath(name: String): String = "config/$name"

    /** A read-only bind mount of the configuration file [name] at [target]. */
    internal fun configMount(
        name: String,
        target: String,
    ): ComposeMount = ComposeMount.bind("./${configPath(name)}", target, readOnly = true)

    /** The S3 endpoint every component of the stack reads the store through. */
    internal val STORE_GATE_ENDPOINT = "${Constants.LocalStack.Services.STORE_GATE}:${Constants.LocalStack.STORE_PORT}"

    /** The signer's address on the project network. */
    internal val STORE_SIGNER_ENDPOINT = "${Constants.LocalStack.Services.STORE_SIGNER}:${Constants.LocalStack.STORE_PORT}"
}
