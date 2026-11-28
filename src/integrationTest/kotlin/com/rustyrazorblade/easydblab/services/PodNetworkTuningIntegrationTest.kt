package com.rustyrazorblade.easydblab.services

import com.fasterxml.jackson.databind.node.ObjectNode
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.DirectReadWait
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.SysctlBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.utils.KubernetesSerialization
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer
import java.time.Duration

/**
 * Applies the pod network sysctl policy to a real K3s that runs with the two drop-ins `up` writes
 * (the API server flags from `start-k3s-server.sh`, the kubelet allowlist from
 * [KubeletSysctlsDropIn]), and proves what admission does to each kind of pod (pod-network-tuning).
 *
 * The API server drop-in is read out of the packaged script, so the test fails when the script's
 * flags stop enabling the API. It has its own container, not [SharedK3s], because both drop-ins must
 * be in place before K3s first starts, and the policy is cluster-wide.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PodNetworkTuningIntegrationTest {
    private companion object {
        const val API_SERVER_DROP_IN = "/etc/rancher/k3s/config.yaml.d/30-admission-policy.yaml"
        val POD_TIMEOUT: Duration = Duration.ofSeconds(120)

        /** The keys a pod keeps at the kernel default (R1), with the value a new network namespace starts with. */
        val KERNEL_DEFAULT_KEYS =
            mapOf(
                "net.ipv4.ip_local_port_range" to "32768 60999",
                "net.ipv4.ip_local_reserved_ports" to "",
            )
    }

    private val container: K3sContainer = SharedK3s.newContainer(Constants.NodeTuning.POD_SYSCTL_PROBE_IMAGE)
    private val namespace = Constants.NodeTuning.POD_SYSCTL_PROBE_NAMESPACE
    private val sysctls = PodNetworkSysctls.packaged()

    private lateinit var client: KubernetesClient
    private lateinit var probe: Fabric8PodSysctlProbe

    @BeforeAll
    fun start() {
        container.withCopyToContainer(Transferable.of(SharedK3s.serverScriptDropIn(API_SERVER_DROP_IN)), API_SERVER_DROP_IN)
        container.withCopyToContainer(Transferable.of(KubeletSysctlsDropIn.render()), Constants.NodeTuning.KUBELET_SYSCTLS_DROP_IN)
        container.start()
        val provider = WorkspaceK8s.provider(container.kubeConfigYaml)
        client = provider.createClient()
        probe = Fabric8PodSysctlProbe(provider)
        PodNetworkTuning(DefaultK8sManifestOperations(provider, EventBus()), probe, EventBus()).apply()
    }

    @AfterAll
    fun stop() {
        if (::client.isInitialized) client.close()
        container.stop()
    }

    /** The sysctls the API server gives [pod] at admission. */
    private fun admittedSysctls(pod: Pod): Map<String, String> = Fabric8PodSysctlProbe.sysctlsOf(admit(pod))

    /** [pod] as the API server would create it (a `dryRun=All` create); nothing is created. */
    private fun admit(pod: Pod): Pod =
        client
            .pods()
            .inNamespace(namespace)
            .resource(pod)
            .dryRun()
            .create()

    private fun pod(
        name: String,
        edit: PodBuilder.() -> PodBuilder = { this },
    ): Pod =
        PodBuilder()
            .withNewMetadata()
            .withName(name)
            .endMetadata()
            .withNewSpec()
            .addNewContainer()
            .withName("main")
            .withImage(Constants.NodeTuning.POD_SYSCTL_PROBE_IMAGE)
            .endContainer()
            .endSpec()
            .edit()
            .build()

    @Test
    fun `a plain pod gets every pod network sysctl`() {
        assertThat(admittedSysctls(pod("plain"))).isEqualTo(sysctls)
    }

    @Test
    fun `a value the pod sets itself is kept and the rest are added`() {
        val own =
            pod("own-keepalive") {
                editSpec()
                    .withNewSecurityContext()
                    .withSysctls(SysctlBuilder().withName("net.ipv4.tcp_keepalive_time").withValue("300").build())
                    .endSecurityContext()
                    .endSpec()
            }

        assertThat(admittedSysctls(own)).isEqualTo(sysctls + ("net.ipv4.tcp_keepalive_time" to "300"))
    }

    /** The policy then patches nothing; with failurePolicy Fail, a CEL error on that path would refuse the pod. */
    @Test
    fun `a pod that sets every injected key itself is admitted with its own values`() {
        val ownValues = sysctls.mapValues { "1" }
        assertThat(ownValues).isNotEqualTo(sysctls)
        val own =
            pod("own-all") {
                editSpec()
                    .withNewSecurityContext()
                    .withSysctls(ownValues.map { (name, value) -> SysctlBuilder().withName(name).withValue(value).build() })
                    .endSecurityContext()
                    .endSpec()
            }

        assertThat(admittedSysctls(own)).isEqualTo(ownValues)
    }

    /**
     * fabric8 drops an empty list when it serializes a pod, so the empty `sysctls` an operator or a
     * Helm chart can send is put back into the JSON tree and posted as written.
     */
    @Test
    fun `a pod with an empty sysctls list gets every pod network sysctl`() {
        val serialization = KubernetesSerialization()
        val tree = serialization.convertValue(pod("empty-sysctls"), ObjectNode::class.java)
        (tree.get("spec") as ObjectNode).putObject("securityContext").putArray("sysctls")
        val uri = "/api/v1/namespaces/$namespace/pods?dryRun=All"

        val admitted = serialization.unmarshal(client.raw(uri, "POST", tree.toString()), Pod::class.java)

        assertThat(Fabric8PodSysctlProbe.sysctlsOf(admitted)).isEqualTo(sysctls)
    }

    @Test
    fun `a pod's other security context fields are kept`() {
        val withUser =
            admit(
                pod("run-as-user") {
                    editSpec()
                        .withNewSecurityContext()
                        .withRunAsUser(1000L)
                        .endSecurityContext()
                        .endSpec()
                },
            )

        assertThat(withUser.spec.securityContext.runAsUser).isEqualTo(1000L)
        assertThat(Fabric8PodSysctlProbe.sysctlsOf(withUser)).isEqualTo(sysctls)
    }

    @Test
    fun `the probe reports the policy's values from the real API server`() {
        assertThat(probe.probe()).isEqualTo(ProbeResult.Admitted(sysctls))
    }

    @Test
    fun `a host network pod is not changed`() {
        assertThat(admittedSysctls(pod("host-network") { editSpec().withHostNetwork(true).endSpec() })).isEmpty()
    }

    @Test
    fun `a real pod starts and its network namespace runs the tuned values and the kernel default port range`() {
        val keys = sysctls.keys + KERNEL_DEFAULT_KEYS.keys
        val command = keys.joinToString("; ") { "echo $it=$(cat /proc/sys/${it.replace('.', '/')})" }
        val reader =
            pod("reads-sysctls") {
                editSpec()
                    .withRestartPolicy("Never")
                    .editFirstContainer()
                    .withCommand("sh", "-c", command)
                    .endContainer()
                    .endSpec()
            }
        val pods = client.pods().inNamespace(namespace)
        pods.resource(reader).create()
        val finished =
            checkNotNull(
                DirectReadWait.awaitPod(client, DirectReadWait.PodRef(namespace, "reads-sysctls"), "has finished", POD_TIMEOUT) {
                    it?.status?.phase in setOf("Succeeded", "Failed")
                },
            )
        assertThat(finished.status.phase)
            .describedAs("the kubelet ran the pod (reason ${finished.status.reason}: ${finished.status.message})")
            .isEqualTo("Succeeded")

        val log = pods.withName("reads-sysctls").log
        // /proc separates the fields of a multi-word value with tabs; the file and the policy use spaces.
        val read =
            log.lines().filter { "=" in it }.associate {
                it.substringBefore("=") to
                    it
                        .substringAfter("=")
                        .trim()
                        .split(Regex("\\s+"))
                        .joinToString(" ")
            }
        assertThat(read).isEqualTo(sysctls + KERNEL_DEFAULT_KEYS)
    }
}
