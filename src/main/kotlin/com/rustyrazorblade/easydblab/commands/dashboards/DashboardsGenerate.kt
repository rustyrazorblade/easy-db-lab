package com.rustyrazorblade.easydblab.commands.dashboards

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File

/**
 * Generate Grafana K8s manifests as YAML files for inspection or debugging.
 *
 * Builds all Grafana resources (ConfigMaps and Deployment) from the dashboard JSON
 * resource files and writes them as YAML to the specified output directory.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "generate",
    description = ["Generate Grafana K8s manifests as YAML files for inspection"],
)
class DashboardsGenerate : PicoBaseCommand() {
    private val manifestBuilder: GrafanaManifestBuilder by inject()

    @Option(
        names = ["-o", "--output"],
        description = ["Output directory (default: k8s/grafana)"],
    )
    var outputDir: String = "k8s/grafana"

    override fun execute() {
        val dir = File(outputDir)
        dir.mkdirs()

        val resources = manifestBuilder.buildAllResources()
        outputHandler.handleMessage("Generating ${resources.size} Grafana manifest(s) to $outputDir...")

        resources.forEach { resource ->
            val kind = resource.kind.lowercase()
            val name = resource.metadata?.name ?: "unknown"
            val filename = "$kind-$name.yaml"
            val yaml = Serialization.asYaml(resource)
            File(dir, filename).writeText(yaml)
        }

        outputHandler.handleMessage("Generated ${resources.size} manifest(s) to $outputDir")
    }
}
