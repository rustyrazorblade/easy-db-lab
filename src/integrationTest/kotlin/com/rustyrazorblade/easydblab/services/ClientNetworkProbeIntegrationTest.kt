package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.ProxiedKubernetesClientFactory
import com.rustyrazorblade.easydblab.network.Ipv4Cidr
import io.fabric8.kubernetes.client.internal.KubeConfigUtils
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [ClientNetworkProbe] against a real API server: the node's pod CIDR and InternalIP are what the
 * kubelet and the node controller report, which no fake reproduces.
 */
class ClientNetworkProbeIntegrationTest {
    @Test
    fun `a node's pod CIDR and InternalIP are client networks, with the overrides after them`(
        @TempDir directory: File,
    ) {
        val kubeconfig = File(directory, "kubeconfig").apply { writeText(SharedK3s.kubeConfigYaml()) }
        val api = KubeApi(kubeconfig.toPath(), KubeConfigUtils.parseConfig(kubeconfig).currentContext)
        val node =
            SharedK3s.client().use {
                it
                    .nodes()
                    .list()
                    .items
                    .single()
            }
        val podCidr = node.spec.podCIDR
        val internalIp =
            node.status.addresses
                .single { it.type == "InternalIP" }
                .address
        val override = Ipv4Cidr.parse("192.168.0.0/16").getOrThrow()

        val networks = ClientNetworkProbe(ProxiedKubernetesClientFactory()).probe(api, listOf(override))

        assertThat(networks.cidrs.map { it.value }).containsExactly(podCidr, "$internalIp/32", override.value)
    }
}
