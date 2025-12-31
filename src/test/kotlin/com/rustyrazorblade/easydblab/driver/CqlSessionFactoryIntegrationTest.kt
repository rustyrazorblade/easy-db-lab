package com.rustyrazorblade.easydblab.driver

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.testcontainers.containers.CassandraContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.net.InetSocketAddress
import java.time.Duration

/**
 * Integration tests for CqlSession connectivity using Testcontainers.
 *
 * These tests verify that CqlSession can successfully connect to a real
 * Cassandra instance and execute queries with the configuration used
 * by DefaultCqlSessionFactory.
 */
@Testcontainers
class CqlSessionFactoryIntegrationTest {
    companion object {
        private const val CASSANDRA_IMAGE = "cassandra:4.1"
        private const val LOCAL_DATACENTER = "datacenter1"

        @Container
        @JvmStatic
        val cassandra: CassandraContainer<*> =
            CassandraContainer(DockerImageName.parse(CASSANDRA_IMAGE))
                .withExposedPorts(9042)
    }

    /**
     * Creates a CqlSession with the same configuration options used by
     * DefaultCqlSessionFactory, but without the SOCKS proxy (for direct testing).
     */
    private fun createDirectSession(): CqlSession {
        val configLoader =
            DriverConfigLoader
                .programmaticBuilder()
                .withDuration(
                    DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT,
                    Duration.ofSeconds(30),
                ).withDuration(
                    DefaultDriverOption.REQUEST_TIMEOUT,
                    Duration.ofSeconds(60),
                ).withString(DefaultDriverOption.LOAD_BALANCING_LOCAL_DATACENTER, LOCAL_DATACENTER)
                .withBoolean(DefaultDriverOption.METADATA_SCHEMA_ENABLED, false)
                .withInt(DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE, 1)
                .withInt(DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE, 1)
                .build()

        return CqlSession
            .builder()
            .withConfigLoader(configLoader)
            .addContactPoint(
                InetSocketAddress(cassandra.host, cassandra.getMappedPort(9042)),
            ).build()
    }

    @Test
    fun `should connect to Cassandra with programmatic configuration`() {
        createDirectSession().use { session ->
            assertThat(session.isClosed).isFalse()
        }
    }

    @Test
    fun `should execute simple query`() {
        createDirectSession().use { session ->
            val result = session.execute("SELECT release_version FROM system.local")
            val row = result.one()

            assertThat(row).isNotNull
            assertThat(row!!.getString("release_version")).isNotBlank()
        }
    }

    @Test
    fun `should list keyspaces`() {
        createDirectSession().use { session ->
            val result = session.execute("SELECT keyspace_name FROM system_schema.keyspaces")
            val keyspaces = result.all().map { it.getString("keyspace_name") }

            assertThat(keyspaces).contains("system", "system_schema")
        }
    }

    @Test
    fun `should create and query keyspace`() {
        createDirectSession().use { session ->
            // Create a test keyspace
            session.execute(
                """
                CREATE KEYSPACE IF NOT EXISTS test_ks
                WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}
                """.trimIndent(),
            )

            // Verify keyspace exists
            val result =
                session.execute(
                    "SELECT keyspace_name FROM system_schema.keyspaces WHERE keyspace_name = 'test_ks'",
                )
            val row = result.one()

            assertThat(row).isNotNull
            assertThat(row!!.getString("keyspace_name")).isEqualTo("test_ks")
        }
    }

    @Test
    fun `should create table and insert data`() {
        createDirectSession().use { session ->
            // Setup keyspace and table
            session.execute(
                """
                CREATE KEYSPACE IF NOT EXISTS integration_test
                WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}
                """.trimIndent(),
            )

            session.execute(
                """
                CREATE TABLE IF NOT EXISTS integration_test.users (
                    id int PRIMARY KEY,
                    name text
                )
                """.trimIndent(),
            )

            // Insert data
            session.execute(
                "INSERT INTO integration_test.users (id, name) VALUES (1, 'Alice')",
            )

            // Query data
            val result =
                session.execute(
                    "SELECT * FROM integration_test.users WHERE id = 1",
                )
            val row = result.one()

            assertThat(row).isNotNull
            assertThat(row!!.getInt("id")).isEqualTo(1)
            assertThat(row.getString("name")).isEqualTo("Alice")
        }
    }

    @Test
    fun `session should report correct datacenter`() {
        createDirectSession().use { session ->
            val metadata = session.metadata
            val nodes = metadata.nodes

            assertThat(nodes).isNotEmpty
            nodes.values.forEach { node ->
                assertThat(node.datacenter).isEqualTo(LOCAL_DATACENTER)
            }
        }
    }
}
