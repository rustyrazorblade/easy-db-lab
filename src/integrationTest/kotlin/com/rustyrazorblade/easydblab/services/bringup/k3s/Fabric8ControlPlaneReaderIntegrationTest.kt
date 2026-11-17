package com.rustyrazorblade.easydblab.services.bringup.k3s

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.providers.aws.pollUntil
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Proves [Fabric8ControlPlaneReader] against a real K3s API server: `/readyz`, a registered Node,
 * the operator Deployment's ready replicas, and a CRD counted only once it is `Established`. A
 * CRD whose names collide with another's exists but is never established (the API server's naming
 * controller refuses its names), which is the real "exists, not yet `Established`" state the gate
 * must not count.
 */
class Fabric8ControlPlaneReaderIntegrationTest {
    private val reader = Fabric8ControlPlaneReader.open(WorkspaceK8s.provider(SharedK3s.kubeConfigYaml()), CniMode.Cilium)
    private val group = "gate.example.com"
    private val accepted = "gatewidgets.$group"
    private val conflicting = "gatethings.$group"

    @AfterEach
    fun cleanup() {
        reader.close()
        SharedK3s.client().use { client ->
            operatorDeployment(client).delete()
            listOf(conflicting, accepted).forEach {
                client
                    .apiextensions()
                    .v1()
                    .customResourceDefinitions()
                    .withName(it)
                    .delete()
            }
        }
    }

    private fun crd(
        plural: String,
        singular: String,
        kind: String,
    ): CustomResourceDefinition =
        CustomResourceDefinitionBuilder()
            .withNewMetadata()
            .withName("$plural.$group")
            .endMetadata()
            .withNewSpec()
            .withGroup(group)
            .withScope("Cluster")
            .withNewNames()
            .withKind(kind)
            .withPlural(plural)
            .withSingular(singular)
            .endNames()
            .addNewVersion()
            .withName("v1")
            .withServed(true)
            .withStorage(true)
            .withNewSchema()
            .withNewOpenAPIV3Schema()
            .withType("object")
            .endOpenAPIV3Schema()
            .endSchema()
            .endVersion()
            .endSpec()
            .build()

    private fun establishedWithin(
        names: List<String>,
        expected: Set<String>,
    ): Set<String> =
        pollUntil(
            operationName = "crd-established",
            maxAttempts = Int.MAX_VALUE,
            interval = Duration.ofMillis(200),
            deadline = Instant.now().plusSeconds(30),
            done = { it == expected },
        ) { reader.establishedCrds(names) }

    @Test
    fun `a ready API server with its own node reads as ready and registered`() {
        val nodeName =
            SharedK3s.client().use {
                it
                    .nodes()
                    .list()
                    .items
                    .first()
                    .metadata.name
            }

        assertThat(reader.apiServerReady()).isTrue()
        assertThat(reader.nodeRegistered(nodeName)).isTrue()
        assertThat(reader.nodeRegistered("db999")).isFalse()
        assertThat(reader.operatorReplicas()).isNull()
    }

    @Test
    fun `a CRD counts once it is Established, and one that exists but is not Established does not`() {
        val names = listOf(accepted, conflicting)
        assertThat(reader.establishedCrds(names)).isEmpty()

        SharedK3s.client().use { client ->
            val crds = client.apiextensions().v1().customResourceDefinitions()
            crds.resource(crd("gatewidgets", "gatewidget", "GateWidget")).create()
            assertThat(establishedWithin(names, setOf(accepted))).containsExactly(accepted)

            // Its singular is the first CRD's, so the API server never accepts its names nor establishes it.
            crds.resource(crd("gatethings", "gatewidget", "GateThing")).create()
            val conditions =
                pollUntil(
                    operationName = "crd-names-refused",
                    maxAttempts = Int.MAX_VALUE,
                    interval = Duration.ofMillis(200),
                    deadline = Instant.now().plusSeconds(30),
                    done = { list -> list.any { it.type == "NamesAccepted" && it.status == "False" } },
                ) {
                    crds
                        .withName(conflicting)
                        .get()
                        .status
                        ?.conditions
                        .orEmpty()
                }
            assertThat(conditions).anyMatch { it.type == "NamesAccepted" && it.status == "False" }
            assertThat(conditions).noneMatch { it.type == "Established" && it.status == "True" }
            assertThat(reader.establishedCrds(names)).containsExactly(accepted)
        }
    }

    /**
     * The operator condition reads a real Deployment's status: not ready while its pod starts,
     * ready once the Deployment reports every desired replica ready. The pod runs the preloaded
     * image and never pulls, so the wait is the Deployment's own.
     */
    @Test
    fun `the operator condition follows the operator Deployment's ready replicas`() {
        val condition = "${Constants.Cilium.OPERATOR_DEPLOYMENT} ready"
        assertThat(reader.networkConditions()[condition]).isFalse()

        SharedK3s.client().use { client ->
            client.resource(operator()).create()
            assertThat(reader.operatorReplicas()?.desired).isEqualTo(1)
            val ready =
                pollUntil(
                    operationName = "operator-ready",
                    maxAttempts = Int.MAX_VALUE,
                    interval = Duration.ofMillis(500),
                    deadline = Instant.now().plusSeconds(120),
                    done = { it },
                ) { reader.networkConditions().getValue(condition) }
            assertThat(ready).isTrue()
            assertThat(reader.operatorReplicas()).isEqualTo(Fabric8ControlPlaneReader.Replicas(1, 1))
        }
    }

    private fun operatorDeployment(client: KubernetesClient) =
        client
            .apps()
            .deployments()
            .inNamespace(Constants.Cilium.NAMESPACE)
            .withName(Constants.Cilium.OPERATOR_DEPLOYMENT)

    private fun operator() =
        DeploymentBuilder()
            .withNewMetadata()
            .withName(Constants.Cilium.OPERATOR_DEPLOYMENT)
            .withNamespace(Constants.Cilium.NAMESPACE)
            .endMetadata()
            .withNewSpec()
            .withReplicas(1)
            .withNewSelector()
            .addToMatchLabels(OPERATOR_LABEL, "operator")
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels(OPERATOR_LABEL, "operator")
            .endMetadata()
            .withNewSpec()
            .withTerminationGracePeriodSeconds(0L)
            .addNewContainer()
            .withName("operator")
            .withImage(SharedK3s.BUSYBOX_IMAGE)
            .withImagePullPolicy("Never")
            .withCommand("sleep", "3600")
            .endContainer()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()

    private companion object {
        const val OPERATOR_LABEL = "io.cilium/app"
    }
}
