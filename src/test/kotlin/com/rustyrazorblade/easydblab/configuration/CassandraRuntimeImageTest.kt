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
     * The moving `<N>-jre` tag follows the newest Ubuntu, whose python3 (3.14 on 26.04, 3.12 on
     * noble) is newer than older releases' cqlsh accepts (5.0.8 takes 3.8-3.11), so cqlsh in the pod
     * stops working. Jammy's python3 3.10 is inside every 4.x and 5.0 cqlsh range, and a named
     * Ubuntu release keeps one python3 for its life. `CassandraPodsTarballIntegrationTest` runs cqlsh.
     */
    @Test
    fun `the base image is pinned to an Ubuntu release whose python3 cqlsh accepts`() {
        val from = dockerfile.single { it.startsWith("FROM ") }

        assertThat(from).isEqualTo("FROM eclipse-temurin:\${JAVA}-jre-jammy")
    }

    @Test
    fun `the image's yq is the version the AMI installs`() {
        val amiYq =
            File("packer/base/install/install_yq.sh")
                .readLines()
                .firstNotNullOfOrNull { Regex("""YQ_VERSION="(\S+)"""").matchEntire(it.trim())?.groupValues?.get(1) }

        assertThat(arg("YQ_VERSION")).isNotNull().isEqualTo(amiYq)
    }

    /**
     * `install_yq.sh` pins only the version (the AMI fetches through the S3 cache), so the version is
     * what the two share; the checksums are the image's own, checked by `sha256sum -c` at build. Each
     * platform the publish job builds needs its own well-formed sum, or that platform's build fails.
     */
    @Test
    fun `the image pins a distinct yq sha256 for every platform it is published for`() {
        val workflow = File(".github/workflows/publish-container.yml").readText()
        val platforms =
            Regex("""context: docker/cassandra-runtime\s+platforms: (\S+)""")
                .find(workflow)
                ?.groupValues
                ?.get(1)
                ?.split(',')
                .orEmpty()
        val sums = platforms.map { arg("YQ_SHA256_${it.substringAfter('/').uppercase()}") }

        assertThat(platforms).containsExactlyInAnyOrder("linux/amd64", "linux/arm64")
        assertThat(sums).allMatch { it != null && Regex("[0-9a-f]{64}").matches(it) }.doesNotHaveDuplicates()
    }

    /**
     * A manual dispatch runs on whatever branch the maintainer picks, and a published tag is never
     * overwritten, so a runtime image pushed from an unreviewed branch would fix that tag for good.
     * The cassandra-runtime job therefore pushes on a dispatch only from main. The Pyroscope agent job
     * keeps the owner's branch-dispatch publish (so a version bump can publish before it merges).
     */
    @Test
    fun `a manual dispatch pushes the runtime image only from main, and the agent image from any branch`() {
        val pushes = pushByJob(File(".github/workflows/publish-container.yml").readLines())

        assertThat(pushes).containsOnlyKeys("publish-pyroscope-agent", "publish-cassandra-runtime")
        assertThat(pushes.getValue("publish-cassandra-runtime"))
            .isEqualTo("\${{ github.event_name != 'workflow_dispatch' || github.ref == 'refs/heads/main' }}")
        assertThat(pushes.getValue("publish-pyroscope-agent")).isEqualTo("true")
    }

    /** Each job's step `with: push:` value, by the job it sits in; never the workflow's `on: push:` trigger. */
    private fun pushByJob(workflow: List<String>): Map<String, String> {
        var job: String? = null
        val pushes = mutableMapOf<String, String>()
        for (line in workflow) {
            Regex("""^ {2}([a-z0-9-]+):$""").matchEntire(line)?.let { job = it.groupValues[1] }
            Regex("""^ {10}push: (.+)$""").matchEntire(line)?.let { match ->
                job?.let { pushes[it] = match.groupValues[1] }
            }
        }
        return pushes
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
