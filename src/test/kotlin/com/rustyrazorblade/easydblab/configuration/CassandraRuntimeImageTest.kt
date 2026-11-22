package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.cassandraversions.ReleaseLines
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The `cassandra-runtime` image is built from `docker/cassandra-runtime/Dockerfile` and published
 * under `<revision>-jre<N>` by `publish-container.yml`, while the cassandra-pods kit pulls
 * [Constants.Kit.CassandraPods.RUNTIME_IMAGE_REVISION]. A Dockerfile change that does not bump both
 * either never reaches the pods or overwrites nothing, and the pods keep the old image.
 */
class CassandraRuntimeImageTest {
    private val dockerfile = File("docker/cassandra-runtime/Dockerfile").readLines()

    private fun arg(name: String): String? =
        dockerfile.firstNotNullOfOrNull { Regex("""ARG $name=(\S+)""").matchEntire(it.trim())?.groupValues?.get(1) }

    @Test
    fun `the Dockerfile's revision is the one the kit pulls`() {
        assertThat(arg("RUNTIME_REVISION")).isEqualTo(Constants.Kit.CassandraPods.RUNTIME_IMAGE_REVISION)
    }

    /**
     * The moving `<N>-jre` tag follows the newest Ubuntu, whose python3 (3.14 on 26.04) is newer
     * than any tarball's cqlsh accepts (3.6-3.13 for 5.0), so cqlsh in the pod stops working. The
     * base is pinned to a named Ubuntu release, which keeps one python3 for the release's life.
     */
    @Test
    fun `the base image is pinned to an Ubuntu release whose python3 cqlsh accepts`() {
        val from = dockerfile.single { it.startsWith("FROM ") }

        assertThat(from).isEqualTo("FROM eclipse-temurin:\${JAVA}-jre-noble")
    }

    @Test
    fun `the image's yq is the version the AMI installs`() {
        val amiYq =
            File("packer/base/install/install_yq.sh")
                .readLines()
                .firstNotNullOfOrNull { Regex("""YQ_VERSION="(\S+)"""").matchEntire(it.trim())?.groupValues?.get(1) }

        assertThat(arg("YQ_VERSION")).isNotNull().isEqualTo(amiYq)
    }

    @Test
    fun `the publish job builds every JDK the release-line table can choose`() {
        val workflow = File(".github/workflows/publish-container.yml").readText()
        val matrix = Regex("""java: \[([^\]]*)]""").find(workflow)?.groupValues?.get(1)

        val tableJdks =
            ReleaseLines
                .load()
                .lines
                .flatMap { it.javaSupported }
                .toSet()

        assertThat(matrix?.split(',')?.map { it.trim().trim('"') }).containsExactlyInAnyOrderElementsOf(tableJdks)
    }
}
