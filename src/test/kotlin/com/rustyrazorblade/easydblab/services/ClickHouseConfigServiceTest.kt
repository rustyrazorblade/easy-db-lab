package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Tests for ClickHouseConfigService sharding configuration logic.
 *
 * These tests verify that the XML manipulation for dynamic shard/replica
 * configuration works correctly for various cluster topologies.
 */
class ClickHouseConfigServiceTest {
    private val service = DefaultClickHouseConfigService()

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
    fun `addShardsToConfigXml includes credentials for distributed queries`() {
        val templateXml = createTemplateXml()

        val result = service.addShardsToConfigXml(templateXml, totalReplicas = 3, replicasPerShard = 3)

        val doc = parseXml(result)
        val credentials = getAllReplicaCredentials(doc)

        // All replicas should have user=default, password=default for distributed queries
        assertThat(credentials).hasSize(3)
        assertThat(credentials).allMatch { it.first == "default" && it.second == "default" }
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

    @Test
    fun `config template uses from_env for s3 cache size instead of hardcoded value`() {
        val realConfigXml = loadConfigXmlFromResources()

        assertThat(realConfigXml).contains("from_env=\"CLICKHOUSE_S3_CACHE_SIZE\"")
        assertThat(realConfigXml).doesNotContain("<max_size>10Gi</max_size>")
    }

    @Test
    fun `config template uses from_env for s3 cache on write`() {
        val realConfigXml = loadConfigXmlFromResources()

        assertThat(realConfigXml).contains("from_env=\"CLICKHOUSE_S3_CACHE_ON_WRITE\"")
    }

    // Tests for calculateShard

    @Test
    fun `calculateShard returns correct shard for ordinals with 3 replicas per shard`() {
        // Ordinals 0, 1, 2 -> shard 1
        assertThat(service.calculateShard(nodeOrdinal = 0, replicasPerShard = 3)).isEqualTo(1)
        assertThat(service.calculateShard(nodeOrdinal = 1, replicasPerShard = 3)).isEqualTo(1)
        assertThat(service.calculateShard(nodeOrdinal = 2, replicasPerShard = 3)).isEqualTo(1)

        // Ordinals 3, 4, 5 -> shard 2
        assertThat(service.calculateShard(nodeOrdinal = 3, replicasPerShard = 3)).isEqualTo(2)
        assertThat(service.calculateShard(nodeOrdinal = 4, replicasPerShard = 3)).isEqualTo(2)
        assertThat(service.calculateShard(nodeOrdinal = 5, replicasPerShard = 3)).isEqualTo(2)

        // Ordinals 6, 7, 8 -> shard 3
        assertThat(service.calculateShard(nodeOrdinal = 6, replicasPerShard = 3)).isEqualTo(3)
        assertThat(service.calculateShard(nodeOrdinal = 7, replicasPerShard = 3)).isEqualTo(3)
        assertThat(service.calculateShard(nodeOrdinal = 8, replicasPerShard = 3)).isEqualTo(3)
    }

    @Test
    fun `calculateShard returns correct shard for ordinals with 2 replicas per shard`() {
        // Ordinals 0, 1 -> shard 1
        assertThat(service.calculateShard(nodeOrdinal = 0, replicasPerShard = 2)).isEqualTo(1)
        assertThat(service.calculateShard(nodeOrdinal = 1, replicasPerShard = 2)).isEqualTo(1)

        // Ordinals 2, 3 -> shard 2
        assertThat(service.calculateShard(nodeOrdinal = 2, replicasPerShard = 2)).isEqualTo(2)
        assertThat(service.calculateShard(nodeOrdinal = 3, replicasPerShard = 2)).isEqualTo(2)

        // Ordinals 4, 5 -> shard 3
        assertThat(service.calculateShard(nodeOrdinal = 4, replicasPerShard = 2)).isEqualTo(3)
        assertThat(service.calculateShard(nodeOrdinal = 5, replicasPerShard = 2)).isEqualTo(3)
    }

    @Test
    fun `calculateShard handles single replica per shard`() {
        assertThat(service.calculateShard(nodeOrdinal = 0, replicasPerShard = 1)).isEqualTo(1)
        assertThat(service.calculateShard(nodeOrdinal = 1, replicasPerShard = 1)).isEqualTo(2)
        assertThat(service.calculateShard(nodeOrdinal = 2, replicasPerShard = 1)).isEqualTo(3)
    }

    // Tests for generateShardEnvContent

    @Test
    fun `generateShardEnvContent creates valid shell env content`() {
        val content = service.generateShardEnvContent(nodeOrdinal = 0, replicasPerShard = 3)

        assertThat(content).contains("export SHARD=1")
        assertThat(content).contains("export REPLICAS_PER_SHARD=3")
        assertThat(content).contains("# Node ordinal: 0")
    }

    @Test
    fun `generateShardEnvContent calculates shard correctly for different ordinals`() {
        // Ordinal 0 with 3 replicas per shard -> shard 1
        val content0 = service.generateShardEnvContent(nodeOrdinal = 0, replicasPerShard = 3)
        assertThat(content0).contains("export SHARD=1")

        // Ordinal 3 with 3 replicas per shard -> shard 2
        val content3 = service.generateShardEnvContent(nodeOrdinal = 3, replicasPerShard = 3)
        assertThat(content3).contains("export SHARD=2")

        // Ordinal 5 with 2 replicas per shard -> shard 3
        val content5 = service.generateShardEnvContent(nodeOrdinal = 5, replicasPerShard = 2)
        assertThat(content5).contains("export SHARD=3")
    }

    @Test
    fun `generateShardEnvContent includes replicas per shard`() {
        val content = service.generateShardEnvContent(nodeOrdinal = 0, replicasPerShard = 5)
        assertThat(content).contains("export REPLICAS_PER_SHARD=5")
    }

    // Helper methods

    private fun loadConfigXmlFromResources(): String =
        javaClass
            .getResourceAsStream("/com/rustyrazorblade/easydblab/configuration/clickhouse/config.xml")
            ?.bufferedReader()
            ?.readText()
            ?: error("Could not load config.xml from resources")

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

    private fun getAllReplicaCredentials(doc: org.w3c.dom.Document): List<Pair<String, String>> {
        val shards = getShardElements(doc)
        return shards.flatMap { shard ->
            val replicas = shard.getElementsByTagName("replica")
            (0 until replicas.length).map { index ->
                val replica = replicas.item(index) as Element
                val user = replica.getElementsByTagName("user").item(0)?.textContent ?: ""
                val password = replica.getElementsByTagName("password").item(0)?.textContent ?: ""
                Pair(user, password)
            }
        }
    }
}
