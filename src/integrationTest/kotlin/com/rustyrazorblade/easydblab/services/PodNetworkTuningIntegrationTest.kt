package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.SysctlBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer
import java.util.concurrent.TimeUnit

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
        const val POD_TIMEOUT_SECONDS = 120L
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
        client.close()
        container.stop()
    }

    /** The sysctls the API server gives [pod] at admission. */
    private fun admittedSysctls(pod: Pod): Map<String, String> = Fabric8PodSysctlProbe.sysctlsOf(probe.admit(pod))

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

    @Test
    fun `a pod's other security context fields are kept`() {
        val withUser =
            probe.admit(
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
    fun `a real pod starts and its network namespace runs the tuned values`() {
        val keys =
            listOf("net.core.somaxconn", "net.ipv4.tcp_max_syn_backlog", "net.ipv4.tcp_slow_start_after_idle", "net.ipv4.tcp_tw_reuse")
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
        pods
            .withName("reads-sysctls")
            .waitUntilCondition({ it?.status?.phase in setOf("Succeeded", "Failed") }, POD_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val log = pods.withName("reads-sysctls").log
        val read = log.lines().filter { "=" in it }.associate { it.substringBefore("=") to it.substringAfter("=") }
        assertThat(read).isEqualTo(keys.associateWith { sysctls.getValue(it) })
    }
}
