package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import org.w3c.dom.Element
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * Service for generating ClickHouse cluster configuration.
 * Handles dynamic shard/replica XML generation.
 */
interface ClickHouseConfigService {
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

    /**
     * Generates the content for a shard environment file for a specific node.
     *
     * The env file contains pre-calculated SHARD and REPLICAS_PER_SHARD values
     * that can be sourced by the ClickHouse container startup script.
     * This moves all shard calculation logic to Kotlin for easier testing and debugging.
     *
     * @param nodeOrdinal The ordinal of the node (0, 1, 2, ...)
     * @param replicasPerShard Number of replicas in each shard
     * @return Content for the env file (shell script format)
     */
    fun generateShardEnvContent(
        nodeOrdinal: Int,
        replicasPerShard: Int,
    ): String

    /**
     * Calculates the shard number for a given node ordinal.
     *
     * Shard numbers are 1-indexed (shard 1, shard 2, etc.).
     * Example with replicasPerShard=3:
     * - ordinals 0, 1, 2 -> shard 1
     * - ordinals 3, 4, 5 -> shard 2
     *
     * @param nodeOrdinal The ordinal of the node (0-indexed)
     * @param replicasPerShard Number of replicas in each shard
     * @return The shard number (1-indexed)
     */
    fun calculateShard(
        nodeOrdinal: Int,
        replicasPerShard: Int,
    ): Int
}

/**
 * Default implementation of ClickHouseConfigService.
 *
 * Handles the generation of ClickHouse cluster configuration including
 * dynamic shard topology based on the number of replicas.
 */
class DefaultClickHouseConfigService : ClickHouseConfigService {
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

                // Add credentials for distributed queries
                val userElement = doc.createElement("user")
                userElement.textContent = "default"
                replicaElement.appendChild(userElement)

                val passwordElement = doc.createElement("password")
                passwordElement.textContent = "default"
                replicaElement.appendChild(passwordElement)

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

    override fun generateShardEnvContent(
        nodeOrdinal: Int,
        replicasPerShard: Int,
    ): String {
        val shard = calculateShard(nodeOrdinal, replicasPerShard)
        return buildString {
            appendLine("# ClickHouse shard configuration")
            appendLine("# Generated by easy-db-lab")
            appendLine("# Node ordinal: $nodeOrdinal")
            appendLine("export SHARD=$shard")
            append("export REPLICAS_PER_SHARD=$replicasPerShard")
        }
    }

    override fun calculateShard(
        nodeOrdinal: Int,
        replicasPerShard: Int,
    ): Int = (nodeOrdinal / replicasPerShard) + 1
}
