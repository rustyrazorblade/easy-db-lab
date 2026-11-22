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
