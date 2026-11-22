package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.RoleSelectors
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
    kitStorageClass: String? = null,
    roleSelectors: RoleSelectors? = null,
    clientNetworks: List<String>? = null,
) {
    /**
     * A cluster with one control node, two db nodes and one app node, enough to resolve every
     * template variable, in the VPC `172.16.0.0/16`. Its kit storage is local volumes, or the provisioned [kitStorageClass]; its
     * role selectors are [roleSelectors], or the owned nodes' when null, and its client networks are
     * [clientNetworks], or the VPC and cluster CIDR when null.
     */
    private val clusterState =
        ClusterState(
            name = "test-cluster",
            initConfig =
                InitConfig(region = "us-west-2", name = "test-cluster", kitStorageClass = kitStorageClass, cidr = "172.16.0.0/16"),
            hosts =
                mapOf(
                    ServerType.Control to listOf(host("control0", "10.0.0.5")),
                    ServerType.Cassandra to listOf(host("db0", "10.0.1.10"), host("db1", "10.0.2.10")),
                    ServerType.Stress to listOf(host("app0", "10.0.3.10")),
                ),
            roleSelectors = roleSelectors,
            clientNetworks = clientNetworks,
        )

    /** The kit's parsed `kit.yaml`. */
    val config: KitConfig by lazy {
        installConfigYaml.decodeFromString(KitConfig.serializer(), resource(KIT_YAML))
    }

    /** The kit's scrape metrics entries. */
    val scrapeMetrics: List<KitMetrics.Scrape> get() = config.metrics.filterIsInstance<KitMetrics.Scrape>()

    private val variables: TemplateVariables
        get() = TemplateVariables.from(state = clusterState, kitName = kitName, storageSize = "10Gi")

    /**
     * The kit's arg defaults keyed by variable, as `kit install` resolves them with no flags: a
     * `\${VAR}` default takes the cluster variable's value, as `KitInstallCommandFactory` does.
     */
    private val argDefaults: Map<String, String>
        get() {
            val vars = variables.toMap()
            return config.args.associate { arg ->
                arg.variable to arg.default.replace(Constants.Kit.SHELL_VAR_PATTERN) { vars[it.groupValues[1]] ?: it.value }
            }
        }

    /**
     * Renders [templateFile] with the kit's arg defaults overlaid by [args], failing on any
     * placeholder left unresolved, and parses every YAML document into a fabric8 object.
     */
    fun render(
        templateFile: String,
        args: Map<String, String> = emptyMap(),
    ): List<HasMetadata> = parseManifests(renderText(templateFile, args))

    /**
     * Renders [templateFile] as `kit install` does, then fills it in as `lib/apply-statefulset.sh`
     * does before parsing: each `@KEY@` of [strings] inside its quoted string, and each `"@KEY@"` of
     * [yaml] (`resources: {} # @KEY@`) replaced, `{}` and comment, by its YAML (the contributed resources JSON).
     */
    fun renderFilled(
        templateFile: String,
        args: Map<String, String> = emptyMap(),
        strings: Map<String, String> = emptyMap(),
        yaml: Map<String, String> = emptyMap(),
    ): List<HasMetadata> {
        val withYaml = yaml.entries.fold(renderText(templateFile, args)) { text, (key, value) -> text.replace("{} # @$key@", value) }
        return parseManifests(strings.entries.fold(withYaml) { text, (key, value) -> text.replace("@$key@", value) })
    }

    /**
     * Renders [templateFile] to text as `kit install` writes it into the kit directory, failing on
     * any placeholder left unresolved.
     */
    fun renderText(
        templateFile: String,
        args: Map<String, String> = emptyMap(),
    ): String {
        val unresolved = mutableListOf<String>()
        val rendered =
            templateService.renderKitTemplate(
                templateContent = resource(templateFile),
                vars = variables,
                extraVars = argDefaults + args,
            ) { unresolved.addAll(it) }
        check(unresolved.isEmpty()) { "Unresolved variables in $templateFile: $unresolved" }
        return rendered
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
    }
}

private val DOCUMENT_SEPARATOR = Regex("(?m)^---\\s*$")

/** Parses every non-blank YAML document in [yaml] into a fabric8 object. */
fun parseManifests(yaml: String): List<HasMetadata> =
    yaml
        .split(DOCUMENT_SEPARATOR)
        .filter { doc -> doc.lines().any { it.isNotBlank() && !it.trimStart().startsWith("#") } }
        .map { Serialization.unmarshal(it, HasMetadata::class.java) }

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
