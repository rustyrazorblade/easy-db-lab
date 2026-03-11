package com.rustyrazorblade.easydblab.spark;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for all Spark writer implementations.
 *
 * Uses TestContainers to spin up Cassandra + Spark (local mode) and verify
 * each writer can generate data and write it successfully.
 *
 * The Spark container runs spark-submit in local[*] mode, so no master/worker
 * cluster is needed. Each writer's shadow JAR is copied into the container
 * and executed with the standard spark.easydblab.* configuration.
 */
@Testcontainers
public class SparkWriterIntegrationTest {

    private static final String CASSANDRA_IMAGE = "cassandra:5.0";
    private static final String SPARK_IMAGE = "apache/spark:3.5.7";
    private static final String KEYSPACE = "spark_test";
    private static final int ROW_COUNT = 100;
    private static final int PARALLELISM = 1;
    private static final int PARTITION_COUNT = 10;
    private static final String LOCAL_DC = "datacenter1";

    private static final Network network = Network.newNetwork();

    @Container
    private static final CassandraContainer<?> cassandra =
        new CassandraContainer<>(DockerImageName.parse(CASSANDRA_IMAGE))
            .withNetwork(network)
            .withNetworkAliases("cassandra")
            .withStartupTimeout(java.time.Duration.ofMinutes(3));

    @Container
    private static final GenericContainer<?> spark = createSparkContainer();

    private static GenericContainer<?> createSparkContainer() {
        var container = new GenericContainer<>(DockerImageName.parse(SPARK_IMAGE))
            .withNetwork(network)
            .withNetworkAliases("spark")
            .withCommand("sleep", "infinity")
            .withStartupTimeout(java.time.Duration.ofMinutes(2));

        copyJarIfExists(container, "connector-writer",
            "spark/connector-writer/build/libs", "connector-writer");
        copyJarIfExists(container, "bulk-writer-sidecar",
            "spark/bulk-writer-sidecar/build/libs", "bulk-writer-sidecar");
        copyJarIfExists(container, "bulk-writer-s3",
            "spark/bulk-writer-s3/build/libs", "bulk-writer-s3");

        return container;
    }

    @Test
    void connectorWriter_writesDataToCassandra() throws Exception {
        String jarPath = findJarInContainer("connector-writer");
        assertThat(jarPath)
            .as("connector-writer JAR must be built. Run: ./gradlew :spark:connector-writer:shadowJar")
            .isNotNull();

        String table = "data_connector_" + System.currentTimeMillis() / 1000;

        var result = sparkSubmit(
            "com.rustyrazorblade.easydblab.spark.StandardConnectorWriter",
            jarPath,
            table
        );

        assertThat(result.getExitCode())
            .as("spark-submit should succeed.\nSTDOUT:\n%s\nSTDERR:\n%s",
                result.getStdout(), result.getStderr())
            .isZero();

        long count = countRows(KEYSPACE, table);
        assertThat(count).isEqualTo(ROW_COUNT);
    }

    @Test
    @Disabled("Requires Cassandra Sidecar container — not yet wired up")
    void bulkWriterSidecar_writesDataToCassandra() throws Exception {
        String jarPath = findJarInContainer("bulk-writer-sidecar");
        assertThat(jarPath)
            .as("bulk-writer-sidecar JAR must be built. Run: ./gradlew :spark:bulk-writer-sidecar:shadowJar")
            .isNotNull();

        String table = "data_sidecar_" + System.currentTimeMillis() / 1000;

        var result = sparkSubmit(
            "com.rustyrazorblade.easydblab.spark.DirectBulkWriter",
            jarPath,
            table
        );

        assertThat(result.getExitCode())
            .as("spark-submit should succeed.\nSTDOUT:\n%s\nSTDERR:\n%s",
                result.getStdout(), result.getStderr())
            .isZero();

        long count = countRows(KEYSPACE, table);
        assertThat(count).isEqualTo(ROW_COUNT);
    }

    @Test
    @Disabled("Requires Cassandra Sidecar + LocalStack S3 — not yet wired up")
    void bulkWriterS3_writesDataToCassandra() throws Exception {
        String jarPath = findJarInContainer("bulk-writer-s3");
        assertThat(jarPath)
            .as("bulk-writer-s3 JAR must be built. Run: ./gradlew :spark:bulk-writer-s3:shadowJar")
            .isNotNull();

        String table = "data_s3_" + System.currentTimeMillis() / 1000;

        // TODO: Start LocalStack container, create S3 bucket, pass endpoint + bucket to spark-submit
        var result = sparkSubmit(
            "com.rustyrazorblade.easydblab.spark.S3BulkWriter",
            jarPath,
            table
            // TODO: add --conf spark.easydblab.s3.bucket=test-bucket
            // TODO: add --conf spark.easydblab.s3.endpoint=http://localstack:4566
        );

        assertThat(result.getExitCode())
            .as("spark-submit should succeed.\nSTDOUT:\n%s\nSTDERR:\n%s",
                result.getStdout(), result.getStderr())
            .isZero();

        long count = countRows(KEYSPACE, table);
        assertThat(count).isEqualTo(ROW_COUNT);
    }

    /**
     * Run spark-submit inside the Spark container with standard easydblab config.
     */
    private org.testcontainers.containers.Container.ExecResult sparkSubmit(
            String mainClass, String jarPath, String table, String... extraConf)
            throws Exception {

        String cassandraHost = "cassandra";

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("/opt/spark/bin/spark-submit");
        cmd.add("--master");
        cmd.add("local[*]");
        cmd.add("--class");
        cmd.add(mainClass);
        cmd.add("--conf");
        cmd.add("spark.easydblab.contactPoints=" + cassandraHost);
        cmd.add("--conf");
        cmd.add("spark.easydblab.keyspace=" + KEYSPACE);
        cmd.add("--conf");
        cmd.add("spark.easydblab.table=" + table);
        cmd.add("--conf");
        cmd.add("spark.easydblab.localDc=" + LOCAL_DC);
        cmd.add("--conf");
        cmd.add("spark.easydblab.rowCount=" + ROW_COUNT);
        cmd.add("--conf");
        cmd.add("spark.easydblab.parallelism=" + PARALLELISM);
        cmd.add("--conf");
        cmd.add("spark.easydblab.partitionCount=" + PARTITION_COUNT);
        cmd.add("--conf");
        cmd.add("spark.easydblab.replicationFactor=1");

        for (String conf : extraConf) {
            cmd.add("--conf");
            cmd.add(conf);
        }

        cmd.add(jarPath);

        return spark.execInContainer(cmd.toArray(new String[0]));
    }

    /**
     * Count rows in a Cassandra table using a direct CQL connection.
     */
    private long countRows(String keyspace, String table) {
        try (CqlSession session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(
                    cassandra.getHost(), cassandra.getMappedPort(9042)))
                .withLocalDatacenter(LOCAL_DC)
                .build()) {
            var row = session.execute(
                "SELECT COUNT(*) FROM " + keyspace + "." + table).one();
            return row != null ? row.getLong(0) : 0;
        }
    }

    /**
     * Resolve the project root directory. Uses the Gradle-provided system property,
     * which avoids fragile parent directory traversal.
     */
    private static Path projectRoot() {
        String root = System.getProperty("project.root");
        if (root != null) {
            return Paths.get(root);
        }
        // Fallback for IDE runs where the system property may not be set
        Path dir = Paths.get(System.getProperty("user.dir"));
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException(
                "Cannot find project root. Run tests via Gradle or set -Dproject.root=...");
        }
        return dir;
    }

    /**
     * Copy a shadow JAR from the build output into the Spark container.
     * Silently skips if the JAR doesn't exist (test will fail with a clear message later).
     */
    private static void copyJarIfExists(GenericContainer<?> container,
            String moduleName, String buildLibsDir, String jarPrefix) {
        Path libsDir = projectRoot().resolve(buildLibsDir);

        if (!Files.isDirectory(libsDir)) {
            return;
        }

        File[] jars = libsDir.toFile().listFiles((dir, name) ->
            name.startsWith(jarPrefix) && name.endsWith(".jar")
                && !name.contains("sources") && !name.contains("javadoc"));

        if (jars != null && jars.length > 0) {
            container.withCopyFileToContainer(
                MountableFile.forHostPath(jars[0].toPath()),
                "/jars/" + jars[0].getName());
        }
    }

    /**
     * Find a JAR file inside the Spark container's /jars/ directory.
     * Returns null if not found.
     */
    private String findJarInContainer(String jarPrefix) {
        try {
            var result = spark.execInContainer("sh", "-c",
                "ls /jars/" + jarPrefix + "*.jar 2>/dev/null | head -1");
            String path = result.getStdout().trim();
            return path.isEmpty() ? null : path;
        } catch (Exception e) {
            return null;
        }
    }
}
