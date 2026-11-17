package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Checks that the built-in Kafka kit keeps its KRaft cluster ID across `stop` and `start`.
 *
 * `stop` deletes the `Kafka` resource, whose status holds the cluster ID, but keeps the brokers'
 * volumes, whose `meta.properties` records it. A new `Kafka` resource gets a new ID, and a broker
 * refuses a volume of another cluster, so `stop` saves the ID in the kit directory and `start`
 * hands it back to Strimzi before Strimzi first reconciles: the resources are applied paused,
 * the status is patched, then reconciliation resumes (Strimzi's recovery procedure for a KRaft
 * cluster on existing volumes). The shell steps run against a stub `kubectl`.
 */
class KafkaKitTest : BaseKoinTest() {
    private val kit by lazy {
        BuiltinKitFixture("kafka", TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))
    }
    private val kitDir by lazy { File(tempDir, "kafka").apply { mkdirs() } }
    private val stub by lazy { StubKubectl(kitDir) }
    private val savedId by lazy { File(kitDir, CLUSTER_ID_FILE) }

    private fun clusterIdStep(phase: List<InstallStep>): InstallStep.Shell =
        phase.filterIsInstance<InstallStep.Shell>().single { CLUSTER_ID_FILE in it.script }

    @Test
    fun `stop saves the running cluster's ID before it deletes the Kafka resource`() {
        stub.respond("get", CLUSTER_ID)

        val exit = stub.run(clusterIdStep(kit.config.stop).script, emptyMap())

        assertThat(exit).withFailMessage(stub.output()).isEqualTo(0)
        assertThat(savedId.readText().trim()).isEqualTo(CLUSTER_ID)
        val save = kit.config.stop.indexOf(clusterIdStep(kit.config.stop))
        val deleteKafka = kit.config.stop.indexOfFirst { it is InstallStep.Delete && it.kind == "kafka" }
        assertThat(save).isLessThan(deleteKafka)
    }

    @Test
    fun `stop with no Kafka resource keeps the ID saved by the last stop`() {
        savedId.writeText("$CLUSTER_ID\n")
        stub.respond("get", "")

        val exit = stub.run(clusterIdStep(kit.config.stop).script, emptyMap())

        assertThat(exit).withFailMessage(stub.output()).isEqualTo(0)
        assertThat(savedId.readText().trim()).isEqualTo(CLUSTER_ID)
    }

    @Test
    fun `start after a stop applies paused, restores the saved ID, then resumes reconciliation`() {
        savedId.writeText("$CLUSTER_ID\n")
        File(kitDir, "kafka.yaml").writeText("kind: Kafka\n")

        val exit = stub.run(clusterIdStep(kit.config.start).script, emptyMap())

        assertThat(exit).withFailMessage(stub.output()).isEqualTo(0)
        val calls = stub.invocations()
        val pausedApply = calls.indexOfFirst { it.startsWith("annotate --local") && "strimzi.io/pause-reconciliation=true" in it }
        val apply = calls.indexOfFirst { it.startsWith("apply -f kafka-paused.yaml") }
        val patch =
            calls.indexOfFirst {
                it.startsWith("patch kafka kafka") && "--subresource status" in it && "\"clusterId\":\"$CLUSTER_ID\"" in it
            }
        val resume = calls.indexOfFirst { it.startsWith("annotate") && "strimzi.io/pause-reconciliation-" in it }
        assertThat(listOf(pausedApply, apply, patch, resume)).doesNotContain(-1).isSorted()
    }

    @Test
    fun `a first start applies the resources as they are, with no pause and no ID`() {
        File(kitDir, "kafka.yaml").writeText("kind: Kafka\n")

        val exit = stub.run(clusterIdStep(kit.config.start).script, emptyMap())

        assertThat(exit).withFailMessage(stub.output()).isEqualTo(0)
        assertThat(stub.invocations()).containsExactly("apply -f kafka.yaml --namespace default")
    }

    @Test
    fun `start applies the Kafka resources only through the step that restores the ID, before waiting for Ready`() {
        val start = kit.config.start
        assertThat(start).doesNotContain(InstallStep.Manifest(template = "kafka.yaml"))
        val restore = start.indexOf(clusterIdStep(start))
        val waitReady = start.indexOfFirst { it is InstallStep.Wait && it.kind == "kafka" }
        assertThat(restore).isNotNegative().isLessThan(waitReady)
    }

    private companion object {
        const val CLUSTER_ID_FILE = "kafka-cluster-id"
        const val CLUSTER_ID = "Kx2b9fQ8T3uY7mN1pL0aZw"
    }
}
