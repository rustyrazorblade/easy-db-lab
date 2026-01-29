package com.rustyrazorblade.easydblab.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.core.YamlDelegate
import org.w3c.dom.Element
import java.io.StringWriter
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * Represents a K8s ConfigMap YAML structure for deserialization.
 */
data class K8sConfigMap(
    val apiVersion: String,
    val kind: String,
    val metadata: ConfigMapMetadata,
    val data: Map<String, String>,
)

data class ConfigMapMetadata(
    val name: String,
    val namespace: String,
    val labels: Map<String, String>? = null,
)

/**
 * Service for generating ClickHouse cluster configuration.
 * Handles ConfigMap template loading and dynamic shard/replica XML generation.
 */
interface ClickHouseConfigService {
    /**
     * Creates ConfigMap data with dynamic shard configuration.
     *
     * @param totalReplicas Total number of ClickHouse nodes
     * @param replicasPerShard Number of replicas in each shard
     * @param basePath Base path for ConfigMap template
     * @return Map containing config.xml and users.xml content
     */
    fun createDynamicConfigMap(
        totalReplicas: Int,
        replicasPerShard: Int,
        basePath: Path,
    ): Map<String, String>

    /**
     * Parses the config.xml template and adds shard/replica entries dynamically.
     *
     * @param configXml The template config.xml content
     * @param totalReplicas Total number of ClickHouse nodes
     * @param replicasPerShard Number of replicas in each shard
     * @return Modified config.xml with dynamic shard configuration
     */
    fun addShardsToConfigXml(
        configXml: String,
        totalReplicas: Int,
        replicasPerShard: Int,
    ): String
}

/**
 * Default implementation of ClickHouseConfigService.
 *
 * Handles the generation of ClickHouse cluster configuration including
 * dynamic shard topology based on the number of replicas.
 */
class DefaultClickHouseConfigService : ClickHouseConfigService {
    private val yamlMapper: ObjectMapper by YamlDelegate()

    companion object {
        private const val CONFIGMAP_FILENAME = "12-clickhouse-server-configmap.yaml"
    }

    override fun createDynamicConfigMap(
        totalReplicas: Int,
        replicasPerShard: Int,
        basePath: Path,
    ): Map<String, String> {
        val configMapPath = basePath.resolve(CONFIGMAP_FILENAME)
        val configMapFile = configMapPath.toFile()
        if (!configMapFile.exists()) {
            error("ConfigMap template not found at $configMapPath")
        }

        val configMap = yamlMapper.readValue(configMapFile, K8sConfigMap::class.java)

        val templateConfigXml =
            configMap.data["config.xml"]
                ?: error("ConfigMap template missing 'config.xml' in data section")
        val usersXml =
            configMap.data["users.xml"]
                ?: error("ConfigMap template missing 'users.xml' in data section")

        val configXml = addShardsToConfigXml(templateConfigXml, totalReplicas, replicasPerShard)
        return mapOf("config.xml" to configXml, "users.xml" to usersXml)
    }

    override fun addShardsToConfigXml(
        configXml: String,
        totalReplicas: Int,
        replicasPerShard: Int,
    ): String {
        val shardCount = totalReplicas / replicasPerShard

        // Parse XML
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(configXml.byteInputStream())

        // Use XPath to find the easy_db_lab element within remote_servers
        val xpath = XPathFactory.newInstance().newXPath()
        val easyDbLab =
            xpath.evaluate("//remote_servers/easy_db_lab", doc, XPathConstants.NODE) as? Element
                ?: error("Could not find <easy_db_lab> element under <remote_servers> in config.xml")

        // Remove existing children (comments, whitespace, any existing shards)
        while (easyDbLab.hasChildNodes()) {
            easyDbLab.removeChild(easyDbLab.firstChild)
        }

        // Add shard elements
        for (shardIndex in 0 until shardCount) {
            val shardElement = doc.createElement("shard")
            val startNode = shardIndex * replicasPerShard

            for (replicaIndex in 0 until replicasPerShard) {
                val nodeIndex = startNode + replicaIndex
                val replicaElement = doc.createElement("replica")

                val hostElement = doc.createElement("host")
                hostElement.textContent = "clickhouse-$nodeIndex.clickhouse.default.svc.cluster.local"
                replicaElement.appendChild(hostElement)

                val portElement = doc.createElement("port")
                portElement.textContent = Constants.ClickHouse.NATIVE_PORT.toString()
                replicaElement.appendChild(portElement)

                shardElement.appendChild(replicaElement)
            }
            easyDbLab.appendChild(shardElement)
        }

        // Serialize back to string
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }
}
