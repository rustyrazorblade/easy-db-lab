package com.rustyrazorblade.easydblab

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.TimeUnit

/**
 * Single, JVM-wide K3s cluster shared by the Kubernetes integration tests that can live in a
 * namespace of their own.
 *
 * A K3s container takes tens of seconds to start, and each test class used to start its own in a
 * `@Container` companion field. This object applies the Testcontainers "singleton container"
 * pattern, as [SharedLocalStack] does: the container starts once, lazily, on first access, and is
 * reused by every test class in the JVM. It is deliberately not a `@Container`, which would stop it
 * at the end of the owning class; Ryuk and the JVM shutdown hook reap it when the JVM exits.
 *
 * Sharing a cluster means sharing its state, so each test class works in its own namespace, created
 * with [createNamespace], and scopes every read and assertion to it. A test that needs cluster-scoped
 * resources (PersistentVolumes, node paths) keeps its own container.
 */
object SharedK3s {
    /** Pod image preloaded into the cluster, so a test's pods never pull from a registry. */
    const val BUSYBOX_IMAGE = "busybox:1.36"

    private const val NAMESPACE_READY_TIMEOUT_SECONDS = 60L

    private val container: K3sContainer by lazy {
        K3sContainer(DockerImageName.parse("rancher/k3s:v1.30.6-k3s1"))
            .withPrivilegedMode(true)
            .withCreateContainerCmdModifier { cmd ->
                cmd.hostConfig!!
                    .withCgroupnsMode("host")
                    .withUlimits(listOf(Ulimit("nofile", 65536L, 65536L)))
            }.withEnv("K3S_SNAPSHOTTER", "native")
            .let { (it as K3sContainer).withPreloadedImages(BUSYBOX_IMAGE) }
            .apply { start() }
    }

    /** The cluster's kubeconfig, pointing at the container's API server port on the host. */
    fun kubeConfigYaml(): String = container.kubeConfigYaml

    /** A new client for the shared cluster. The caller owns it and closes it. */
    fun client(): KubernetesClient = KubernetesClientBuilder().withConfig(Config.fromKubeconfig(kubeConfigYaml())).build()

    /**
     * Creates [name] and waits for its `default` ServiceAccount, which the API server requires
     * before it admits a pod into the namespace.
     */
    fun createNamespace(name: String) {
        client().use { client ->
            client
                .resource(
                    NamespaceBuilder()
                        .withNewMetadata()
                        .withName(name)
                        .endMetadata()
                        .build(),
                ).create()
            client
                .serviceAccounts()
                .inNamespace(name)
                .withName("default")
                .waitUntilCondition({ it != null }, NAMESPACE_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }
}
