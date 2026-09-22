package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitMetrics
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.TemplateVariables
import com.rustyrazorblade.easydblab.services.installConfigYaml
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.utils.Serialization

/**
 * Loads a built-in kit from the classpath exactly as `kit install` does: `kit.yaml` through the
 * production decoder, and each manifest template through [TemplateService.renderKitTemplate] with
 * the kit's own arg defaults. Tests use it to check the contracts that span a kit's files — a
 * scrape `pod-selector` that must match the pod labels, a NodePort that must match the declared
 * endpoint — which no single file can check on its own.
 */
class BuiltinKitFixture(
    private val kitName: String,
    private val templateService: TemplateService,
) {
    /** A cluster with one control node and two db nodes, enough to resolve every template variable. */
    private val clusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            initConfig = InitConfig(region = "us-west-2", name = "test-cluster"),
            hosts =
                mapOf(
                    ServerType.Control to listOf(host("control0", "10.0.0.5")),
                    ServerType.Cassandra to listOf(host("db0", "10.0.1.10"), host("db1", "10.0.2.10")),
                ),
        )

    /** The kit's parsed `kit.yaml`. */
    val config: KitConfig by lazy {
        installConfigYaml.decodeFromString(KitConfig.serializer(), resource(KIT_YAML))
    }

    /** The kit's scrape metrics entries. */
    val scrapeMetrics: List<KitMetrics.Scrape> get() = config.metrics.filterIsInstance<KitMetrics.Scrape>()

    /** The kit's arg defaults keyed by variable, as `kit install` resolves them with no flags. */
    private val argDefaults: Map<String, String>
        get() = config.args.associate { it.variable to it.default }

    /**
     * Renders [templateFile] with the kit's arg defaults overlaid by [args], failing on any
     * placeholder left unresolved, and parses every YAML document into a fabric8 object.
     */
    fun render(
        templateFile: String,
        args: Map<String, String> = emptyMap(),
    ): List<HasMetadata> {
        val unresolved = mutableListOf<String>()
        val rendered =
            templateService.renderKitTemplate(
                templateContent = resource(templateFile),
                vars = TemplateVariables.from(state = clusterState, kitName = kitName, storageSize = "10Gi"),
                extraVars = argDefaults + args,
            ) { unresolved.addAll(it) }
        check(unresolved.isEmpty()) { "Unresolved variables in $templateFile: $unresolved" }
        return rendered
            .split(DOCUMENT_SEPARATOR)
            .filter { it.isNotBlank() }
            .map { Serialization.unmarshal(it, HasMetadata::class.java) }
    }

    /** Reads a file from the kit's resource directory. */
    fun resource(file: String): String =
        BuiltinKitFixture::class.java.classLoader
            .getResourceAsStream("$RESOURCE_BASE/$kitName/$file")
            ?.bufferedReader()
            ?.readText()
            ?: error("$file not found in built-in kit $kitName")

    private fun host(
        alias: String,
        privateIp: String,
    ) = ClusterHost(publicIp = "", privateIp = privateIp, alias = alias, availabilityZone = "us-west-2a")

    private companion object {
        const val RESOURCE_BASE = "com/rustyrazorblade/easydblab/kits"
        const val KIT_YAML = "kit.yaml"
        val DOCUMENT_SEPARATOR = Regex("(?m)^---\\s*$")
    }
}

/**
 * Parses a comma-separated `key=value` label selector, the form kits use for a scrape
 * `pod-selector` and a runtime `selector`.
 */
fun parseLabelSelector(selector: String): Map<String, String> =
    selector
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .associate { pair -> pair.substringBefore("=").trim() to pair.substringAfter("=").trim() }
