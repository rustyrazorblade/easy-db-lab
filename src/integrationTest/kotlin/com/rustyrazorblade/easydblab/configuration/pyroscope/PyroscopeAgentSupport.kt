package com.rustyrazorblade.easydblab.configuration.pyroscope

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ObservabilityBackends
import org.assertj.core.api.Assertions.assertThat
import org.testcontainers.containers.GenericContainer
import java.io.File
import java.net.URLEncoder

/**
 * What the Pyroscope Java-agent integration tests share: the pinned agent jar, a JVM that keeps a
 * CPU busy, the agent's upload error lines and a query for the profiles that arrived. Each test
 * starts its own server and JVM; this holds only what they read and check the same way.
 */
internal object PyroscopeAgentSupport {
    const val JDK_IMAGE = "eclipse-temurin:21-jdk"
    const val APP_DIR = "/app"
    const val AGENT_STARTED = ".*Profiling started.*"

    /** The prefix agent 2.3.0 logs for every failed upload: a refused status or a connection error. */
    const val UPLOAD_ERROR = "Error uploading snapshot: "
    const val GAVE_UP = "Gave up uploading"
    const val EMPTY_RING = "empty ring"

    private const val HTTP_OK = 200
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_SERVER_ERROR = 500
    private val REFUSED_UPLOAD = Regex("$UPLOAD_ERROR(\\d{3}) ")

    /** A JVM that keeps one CPU busy, so every snapshot holds CPU samples. */
    val BUSY_SOURCE =
        """
        public class Busy {
            public static void main(String[] args) {
                long x = 0;
                while (true) {
                    for (int i = 0; i < 1_000_000; i++) {
                        x += i * 31L ^ (x >>> 3);
                    }
                    if (x == 42) System.out.println(x);
                }
            }
        }
        """.trimIndent()

    /**
     * The pinned agent jar (`io.pyroscope:agent`, the same jar as the release the AMI and EMR
     * install), which Gradle resolves from Maven Central into its cache and passes as a system
     * property. Its version must be the one the cluster installs.
     */
    fun agentJar(): File {
        assertThat(System.getProperty("easydblab.test.pyroscopeAgentVersion"))
            .describedAs("the agent version in libs.versions.toml")
            .isEqualTo(Constants.PyroscopeJavaAgent.VERSION)
        val jar = File(checkNotNull(System.getProperty("easydblab.test.pyroscopeAgentJar")) { "run through Gradle" })
        check(jar.isFile) { "no agent jar at $jar" }
        return jar
    }

    /**
     * The agent's log lines for each snapshot it dropped. Agent 2.3.0 logs `Error uploading
     * snapshot: <status> <body>` for every refused upload and retries only a 429 or a 5xx, so any
     * other status is a dropped snapshot; `Gave up uploading` is one that ran out of tries.
     */
    fun droppedUploads(log: String): List<String> =
        log.lines().filter { line ->
            val status =
                REFUSED_UPLOAD
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
            (status != null && status != HTTP_TOO_MANY_REQUESTS && status < HTTP_SERVER_ERROR) || GAVE_UP in line
        }

    /** CPU samples [tenant] sees for [service] on [pyroscope] between [fromSecond] and [untilSecond]. */
    fun cpuTicks(
        pyroscope: GenericContainer<*>,
        tenant: String,
        service: String,
        fromSecond: Long,
        untilSecond: Long,
    ): Long {
        val query =
            URLEncoder.encode("process_cpu:cpu:nanoseconds:cpu:nanoseconds{service_name=\"$service\"}", Charsets.UTF_8)
        val url =
            "${ObservabilityBackends.baseUrl(pyroscope, PyroscopeManifestBuilder.SERVER_PORT)}/pyroscope/render" +
                "?query=$query&from=$fromSecond&until=$untilSecond&format=json"
        val response = runCatching { ObservabilityBackends.get(url, tenant) }.getOrNull()
        if (response?.statusCode() != HTTP_OK) return 0
        return Regex("\"numTicks\":(\\d+)")
            .find(response.body())
            ?.groupValues
            ?.get(1)
            ?.toLong() ?: 0
    }
}
