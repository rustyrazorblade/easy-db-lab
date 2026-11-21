package com.rustyrazorblade.easydblab

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.util.Base64

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
    /**
     * The K3s image every K3s integration test starts. It must match `K3S_VERSION` in
     * `packer/base/install/install_k3s.sh`, so the tests run the Kubernetes release the clusters
     * run; the Docker tag writes K3s's `+` as `-`.
     *
     * On 1.35 the MutatingAdmissionPolicy API is beta and off. The shared cluster leaves it off;
     * `PodNetworkTuningIntegrationTest` starts its own K3s with the API server drop-in that
     * `start-k3s-server.sh` writes. When this image reaches 1.36 the API is on by default, the
     * drop-in goes, and `PodNetworkSysctlPolicyBuilder` moves to `admissionregistration.k8s.io/v1`.
     */
    const val K3S_IMAGE = "rancher/k3s:v1.35.1-k3s1"

    private const val LOG_TAIL_CHARS = 6000

    /** Pod image preloaded into the cluster, so a test's pods never pull from a registry. */
    const val BUSYBOX_IMAGE = "busybox:1.36"

    /** The documents web server image, preloaded for the web server test. */
    const val WEB_SERVER_IMAGE = Constants.Grafana.Documents.WEB_SERVER_IMAGE

    private const val SERVER_SCRIPT_RESOURCE = "/com/rustyrazorblade/easydblab/services/start-k3s-server.sh"

    private val container: K3sContainer by lazy {
        newContainer(BUSYBOX_IMAGE, WEB_SERVER_IMAGE).apply { start() }
    }

    /**
     * A new, unstarted K3s container configured to run in Docker (privileged, the host cgroup
     * namespace, the native snapshotter) with [images] preloaded. For a test that needs a cluster of
     * its own, for instance one with config drop-ins in place before K3s first starts.
     */
    fun newContainer(vararg images: String): K3sContainer =
        K3sContainer(DockerImageName.parse(K3S_IMAGE))
            .withPrivilegedMode(true)
            .withCreateContainerCmdModifier { cmd ->
                checkNotNull(cmd.hostConfig) { "the create command has no host config" }
                    .withCgroupnsMode("host")
                    .withUlimits(listOf(Ulimit("nofile", 65536L, 65536L)))
            }.withEnv("K3S_SNAPSHOTTER", "native")
            .let { (it as K3sContainer).withPreloadedImages(*images) }

    /**
     * The body of the quoted heredoc `start-k3s-server.sh` writes to [path], so a test runs K3s with
     * exactly the drop-in a cluster gets and fails when the script stops writing it.
     */
    fun serverScriptDropIn(path: String): String {
        val script =
            checkNotNull(SharedK3s::class.java.getResourceAsStream(SERVER_SCRIPT_RESOURCE)) {
                "$SERVER_SCRIPT_RESOURCE is not packaged"
            }.use { it.readBytes().decodeToString() }
        val start = script.indexOf("cat > $path <<'EOF'\n")
        check(start >= 0) { "start-k3s-server.sh does not write $path" }
        return script.substring(start).substringAfter("<<'EOF'\n").substringBefore("\nEOF\n") + "\n"
    }

    /** The cluster's kubeconfig, pointing at the container's API server port on the host. */
    fun kubeConfigYaml(): String = container.kubeConfigYaml

    /** A new client for the shared cluster. The caller owns it and closes it. */
    fun client(): KubernetesClient = KubernetesClientBuilder().withConfig(Config.fromKubeconfig(kubeConfigYaml())).build()

    /** The last part of the K3s container's log, for a failure report. */
    fun logTail(chars: Int = LOG_TAIL_CHARS): String = container.logs.takeLast(chars)

    /**
     * Creates [name] and makes it ready to admit pods, through [ensurePodAdmission].
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
            ensurePodAdmission(client, name)
        }
    }

    /** The ConfigMap a pod's projected ServiceAccount token volume reads the cluster CA from. */
    const val ROOT_CA_CONFIG_MAP = "kube-root-ca.crt"

    /** The key of the cluster CA in [ROOT_CA_CONFIG_MAP]. */
    const val ROOT_CA_KEY = "ca.crt"

    private const val DEFAULT_SERVICE_ACCOUNT = "default"

    /**
     * Creates the two objects a pod in [namespace] needs that the kube-controller-manager would
     * otherwise create asynchronously: the `default` ServiceAccount, without which the API server
     * refuses the pod, and the [ROOT_CA_CONFIG_MAP] ConfigMap (the server CA from the client's
     * kubeconfig), without which the kubelet cannot mount the pod's projected token volume. On a
     * shared cluster under a full integration run the controller's queues lag by over a minute, so
     * waiting for it made test setup time out. Either object may already exist when the controller
     * won the race; that is success. The token itself is projected by the kubelet, so no token
     * Secret is needed.
     */
    fun ensurePodAdmission(
        client: KubernetesClient,
        namespace: String,
    ) {
        createIfMissing {
            client
                .serviceAccounts()
                .inNamespace(namespace)
                .resource(
                    ServiceAccountBuilder()
                        .withNewMetadata()
                        .withName(DEFAULT_SERVICE_ACCOUNT)
                        .endMetadata()
                        .build(),
                ).create()
        }
        // K3s's root CA publisher copies the server CA, which is also the kubeconfig's CA.
        val serverCa = String(Base64.getDecoder().decode(requireNotNull(client.configuration.caCertData)))
        createIfMissing {
            client
                .configMaps()
                .inNamespace(namespace)
                .resource(
                    ConfigMapBuilder()
                        .withNewMetadata()
                        .withName(ROOT_CA_CONFIG_MAP)
                        .endMetadata()
                        .addToData(ROOT_CA_KEY, serverCa)
                        .build(),
                ).create()
        }
    }

    private fun createIfMissing(create: () -> Unit) {
        try {
            create()
        } catch (e: KubernetesClientException) {
            if (e.code != Constants.HttpStatus.CONFLICT) throw e
        }
    }
}
