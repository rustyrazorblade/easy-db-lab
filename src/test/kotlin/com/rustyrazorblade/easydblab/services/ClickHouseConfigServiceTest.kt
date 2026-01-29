package com.rustyrazorblade.easydblab.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.rustyrazorblade.easydblab.core.YamlDelegate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Tests for ClickHouseConfigService sharding configuration logic.
 *
 * These tests verify that the XML manipulation for dynamic shard/replica
 * configuration works correctly for various cluster topologies using
 * the actual ConfigMap template from resources.
 */
class ClickHouseConfigServiceTest {
    private val service = DefaultClickHouseConfigService()

    companion object {
        private const val CONFIGMAP_RESOURCE_PATH =
            "/com/rustyrazorblade/easydblab/commands/k8s/clickhouse/12-clickhouse-server-configmap.yaml"

        private val yamlMapper: ObjectMapper by YamlDelegate()
    }

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `addShardsToConfigXml creates correct topology for 6 nodes with 3 replicas per shard`() {
        val templateXml = createTemplateXml()

        val result = service.addShardsToConfigXml(templateXml, totalReplicas = 6, replicasPerShard = 3)

        // Parse result and verify
        val doc = parseXml(result)
        val shards = getShardElements(doc)

        assertThat(shards).hasSize(2) // 6 / 3 = 2 shards

        // Verify shard 0 has replicas 0, 1, 2
        val shard0Replicas = getReplicaHosts(shards[0])
        assertThat(shard0Replicas).containsExactly(
            "clickhouse-0.clickhouse.default.svc.cluster.local",
            "clickhouse-1.clickhouse.default.svc.cluster.local",
            "clickhouse-2.clickhouse.default.svc.cluster.local",
        )

        // Verify shard 1 has replicas 3, 4, 5
        val shard1Replicas = getReplicaHosts(shards[1])
        assertThat(shard1Replicas).containsExactly(
            "clickhouse-3.clickhouse.default.svc.cluster.local",
            "clickhouse-4.clickhouse.default.svc.cluster.local",
            "clickhouse-5.clickhouse.default.svc.cluster.local",
        )
    }

    @Test
    fun `addShardsToConfigXml creates correct topology for 12 nodes with 3 replicas per shard`() {
        val templateXml = createTemplateXml()

        val result = service.addShardsToConfigXml(templateXml, totalReplicas = 12, replicasPerShard = 3)

        val doc = parseXml(result)
        val shards = getShardElements(doc)

        assertThat(shards).hasSize(4) // 12 / 3 = 4 shards

        // Verify shard distribution
        assertThat(getReplicaHosts(shards[0])).containsExactly(
            "clickhouse-0.clickhouse.default.svc.cluster.local",
            "clickhouse-1.clickhouse.default.svc.cluster.local",
            "clickhouse-2.clickhouse.default.svc.cluster.local",
        )
        assertThat(getReplicaHosts(shards[1])).containsExactly(
            "clickhouse-3.clickhouse.default.svc.cluster.local",
            "clickhouse-4.clickhouse.default.svc.cluster.local",
            "clickhouse-5.clickhouse.default.svc.cluster.local",
        )
        assertThat(getReplicaHosts(shards[2])).containsExactly(
            "clickhouse-6.clickhouse.default.svc.cluster.local",
            "clickhouse-7.clickhouse.default.svc.cluster.local",
            "clickhouse-8.clickhouse.default.svc.cluster.local",
        )
        assertThat(getReplicaHosts(shards[3])).containsExactly(
            "clickhouse-9.clickhouse.default.svc.cluster.local",
            "clickhouse-10.clickhouse.default.svc.cluster.local",
            "clickhouse-11.clickhouse.default.svc.cluster.local",
        )
    }

    @Test
    fun `addShardsToConfigXml creates single shard for 3 nodes with 3 replicas per shard`() {
        val templateXml = createTemplateXml()

        val result = service.addShardsToConfigXml(templateXml, totalReplicas = 3, replicasPerShard = 3)

        val doc = parseXml(result)
        val shards = getShardElements(doc)

        assertThat(shards).hasSize(1) // 3 / 3 = 1 shard

        assertThat(getReplicaHosts(shards[0])).containsExactly(
            "clickhouse-0.clickhouse.default.svc.cluster.local",
            "clickhouse-1.clickhouse.default.svc.cluster.local",
            "clickhouse-2.clickhouse.default.svc.cluster.local",
        )
    }

    @Test
    fun `addShardsToConfigXml creates topology with 2 replicas per shard`() {
        val templateXml = createTemplateXml()

        val result = service.addShardsToConfigXml(templateXml, totalReplicas = 6, replicasPerShard = 2)

        val doc = parseXml(result)
        val shards = getShardElements(doc)

        assertThat(shards).hasSize(3) // 6 / 2 = 3 shards

        assertThat(getReplicaHosts(shards[0])).containsExactly(
            "clickhouse-0.clickhouse.default.svc.cluster.local",
            "clickhouse-1.clickhouse.default.svc.cluster.local",
        )
        assertThat(getReplicaHosts(shards[1])).containsExactly(
            "clickhouse-2.clickhouse.default.svc.cluster.local",
            "clickhouse-3.clickhouse.default.svc.cluster.local",
        )
        assertThat(getReplicaHosts(shards[2])).containsExactly(
            "clickhouse-4.clickhouse.default.svc.cluster.local",
            "clickhouse-5.clickhouse.default.svc.cluster.local",
        )
    }

    @Test
    fun `addShardsToConfigXml sets correct port for all replicas`() {
        val templateXml = createTemplateXml()

        val result = service.addShardsToConfigXml(templateXml, totalReplicas = 3, replicasPerShard = 3)

        val doc = parseXml(result)
        val ports = getAllReplicaPorts(doc)

        // All replicas should have port 9000
        assertThat(ports).allMatch { it == "9000" }
    }

    @Test
    fun `addShardsToConfigXml throws error for missing remote_servers element`() {
        val invalidXml = "<clickhouse><some_other_element/></clickhouse>"

        assertThatThrownBy {
            service.addShardsToConfigXml(invalidXml, totalReplicas = 3, replicasPerShard = 3)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("remote_servers")
    }

    @Test
    fun `addShardsToConfigXml throws error for missing easy_db_lab element`() {
        val invalidXml =
            """
            <clickhouse>
                <remote_servers>
                    <other_cluster/>
                </remote_servers>
            </clickhouse>
            """.trimIndent()

        assertThatThrownBy {
            service.addShardsToConfigXml(invalidXml, totalReplicas = 3, replicasPerShard = 3)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("easy_db_lab")
    }

    // Tests using real config.xml from resources

    @Test
    fun `addShardsToConfigXml with real template creates correct topology`() {
        val realConfigXml = loadConfigXmlFromResources()

        val result = service.addShardsToConfigXml(realConfigXml, totalReplicas = 6, replicasPerShard = 3)

        val doc = parseXml(result)
        val shards = getShardElements(doc)

        assertThat(shards).hasSize(2)
        assertThat(getReplicaHosts(shards[0])).containsExactly(
            "clickhouse-0.clickhouse.default.svc.cluster.local",
            "clickhouse-1.clickhouse.default.svc.cluster.local",
            "clickhouse-2.clickhouse.default.svc.cluster.local",
        )
        assertThat(getReplicaHosts(shards[1])).containsExactly(
            "clickhouse-3.clickhouse.default.svc.cluster.local",
            "clickhouse-4.clickhouse.default.svc.cluster.local",
            "clickhouse-5.clickhouse.default.svc.cluster.local",
        )
    }

    @Test
    fun `addShardsToConfigXml preserves other config elements`() {
        val realConfigXml = loadConfigXmlFromResources()

        val result = service.addShardsToConfigXml(realConfigXml, totalReplicas = 3, replicasPerShard = 3)

        // Verify other config elements are preserved
        assertThat(result).contains("<logger>")
        assertThat(result).contains("<storage_configuration>")
        assertThat(result).contains("<zookeeper>")
        assertThat(result).contains("<macros>")
        assertThat(result).contains("<distributed_ddl>")
        assertThat(result).contains("<prometheus>")
    }

    // Tests for createDynamicConfigMap

    @Test
    fun `createDynamicConfigMap loads template and generates correct config`() {
        // Copy ConfigMap from resources to temp dir
        val configMapContent =
            javaClass
                .getResourceAsStream(CONFIGMAP_RESOURCE_PATH)
                ?.bufferedReader()
                ?.readText()
                ?: error("Could not load ConfigMap from resources")
        Files.writeString(
            tempDir.resolve("12-clickhouse-server-configmap.yaml"),
            configMapContent,
        )

        val configMap =
            service.createDynamicConfigMap(
                totalReplicas = 6,
                replicasPerShard = 3,
                basePath = tempDir,
            )

        assertThat(configMap).containsKey("config.xml")
        assertThat(configMap).containsKey("users.xml")

        // Verify config.xml has correct shards
        val configXml = configMap["config.xml"]!!
        val doc = parseXml(configXml)
        val shards = getShardElements(doc)
        assertThat(shards).hasSize(2)

        // Verify users.xml is preserved
        val usersXml = configMap["users.xml"]!!
        assertThat(usersXml).contains("<users>")
        assertThat(usersXml).contains("<default>")
    }

    @Test
    fun `createDynamicConfigMap throws error when template not found`() {
        assertThatThrownBy {
            service.createDynamicConfigMap(
                totalReplicas = 3,
                replicasPerShard = 3,
                basePath = tempDir,
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ConfigMap template not found")
    }

    // Helper methods

    private fun loadConfigXmlFromResources(): String {
        val yamlContent =
            javaClass
                .getResourceAsStream(CONFIGMAP_RESOURCE_PATH)
                ?.bufferedReader()
                ?.readText()
                ?: error("Could not load ConfigMap from resources")

        val configMap = yamlMapper.readValue(yamlContent, K8sConfigMap::class.java)
        return configMap.data["config.xml"] ?: error("Missing config.xml in ConfigMap")
    }

    private fun createTemplateXml(): String =
        """
        <clickhouse>
            <remote_servers>
                <easy_db_lab>
                    <!-- Shards are generated dynamically -->
                </easy_db_lab>
            </remote_servers>
        </clickhouse>
        """.trimIndent()

    private fun parseXml(xml: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        return builder.parse(xml.byteInputStream())
    }

    private fun getShardElements(doc: org.w3c.dom.Document): List<Element> {
        // Navigate to <remote_servers> -> <easy_db_lab> to get only cluster shards
        // (not the <shard> element in <macros>)
        val remoteServers =
            doc.getElementsByTagName("remote_servers").item(0) as? Element
                ?: return emptyList()
        val easyDbLab =
            findChildElement(remoteServers, "easy_db_lab")
                ?: return emptyList()

        // Get direct child shard elements
        val children = easyDbLab.childNodes
        return (0 until children.length)
            .map { children.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == "shard" }
    }

    private fun findChildElement(
        parent: Element,
        tagName: String,
    ): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && child.tagName == tagName) {
                return child
            }
        }
        return null
    }

    private fun getReplicaHosts(shard: Element): List<String> {
        val replicas = shard.getElementsByTagName("replica")
        return (0 until replicas.length).map { index ->
            val replica = replicas.item(index) as Element
            val host = replica.getElementsByTagName("host").item(0)
            host.textContent
        }
    }

    private fun getAllReplicaPorts(doc: org.w3c.dom.Document): List<String> {
        val ports = doc.getElementsByTagName("port")
        return (0 until ports.length)
            .map { ports.item(it).textContent }
            .filter { it.matches(Regex("\\d+")) } // Only numeric ports (not env vars)
    }
}
