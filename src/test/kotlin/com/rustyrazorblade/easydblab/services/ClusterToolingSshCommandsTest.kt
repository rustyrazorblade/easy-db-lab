package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Each [KubectlService] and [HelmService] method run through the real [SshClusterTooling] against a
 * mocked [RemoteOperationsService], asserting the exact remote command and whether its output is
 * shown. The expected strings are the ones the services built themselves before the seam existed, so
 * AWS runs what it always ran. The only differences are the three the change names: the temporary input
 * file is `/tmp/easydblab-input-<uuid>.yaml` for manifests and helm values alike, `createNamespace`
 * is two calls instead of a shell pipe, and the kubectl `rm -f` of that file runs with `output = false`,
 * so the blank line that followed each apply is gone.
 */
internal class ClusterToolingSshCommandsTest {
    @TempDir
    lateinit var stateDir: File

    @TempDir
    lateinit var tempDir: File

    private val remoteOps: RemoteOperationsService = mock()
    private lateinit var kubectl: KubectlService
    private lateinit var helm: HelmService
    private val kubeconfig = "KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG}"

    /** What [remoteOps] answers, by the command it was asked to run; commands not listed answer with nothing. */
    private val answers = mutableMapOf<String, String>()

    /** Every remote command run, in order, with whether its output was shown. */
    private val commands = mutableListOf<Pair<String, Boolean>>()

    @BeforeEach
    fun setup() {
        val stateManager = ClusterStateManager(File(stateDir, "state.json"))
        stateManager.save(
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                hosts = mapOf(ServerType.Control to listOf(ClusterHost("54.1.2.3", "10.0.0.1", "control0", "us-west-2a"))),
            ),
        )
        whenever(remoteOps.executeRemotely(any(), any(), any(), any())).doAnswer {
            val command = it.getArgument<String>(1)
            commands += command to it.getArgument<Boolean>(2)
            Response(answers[command].orEmpty())
        }
        val tooling = SshClusterTooling(remoteOps, stateManager, tempDir.toPath())
        kubectl = DefaultKubectlService(tooling)
        helm = DefaultHelmService(tooling)
    }

    private fun uploadedPath(): String = argumentCaptor<String>().also { verify(remoteOps).upload(any(), any(), it.capture()) }.firstValue

    @Test
    fun `applyUrl applies server-side from the url`() {
        kubectl.applyUrl("https://example.com/manifest.yaml")

        assertThat(commands).containsExactly("$kubeconfig kubectl apply --server-side -f https://example.com/manifest.yaml" to true)
    }

    @Test
    fun `applyKustomize applies the kustomization at the url`() {
        kubectl.applyKustomize("https://example.com/kustomize")

        assertThat(commands).containsExactly("$kubeconfig kubectl apply -k https://example.com/kustomize" to true)
    }

    @Test
    fun `applyContent uploads the manifest, applies the uploaded file, then removes it`() {
        kubectl.applyContent("apiVersion: v1\nkind: ConfigMap")

        val path = uploadedPath()
        assertThat(path).matches("/tmp/easydblab-input-[0-9a-f-]{36}\\.yaml")
        assertThat(commands).containsExactly(
            "$kubeconfig kubectl apply -f $path" to true,
            "rm -f $path" to false,
        )
    }

    @Test
    fun `createNamespace renders the namespace quietly, then applies it from an uploaded file`() {
        val rendered = "apiVersion: v1\nkind: Namespace\nmetadata:\n  name: cache\n"
        answers["$kubeconfig kubectl create namespace cache --dry-run=client -o yaml"] = rendered
        var uploaded: String? = null
        doAnswer { uploaded = Files.readString(it.getArgument<Path>(1)) }.whenever(remoteOps).upload(any(), any(), any())

        kubectl.createNamespace("cache")

        val path = uploadedPath()
        assertThat(uploaded).isEqualTo(rendered)
        assertThat(commands).containsExactly(
            "$kubeconfig kubectl create namespace cache --dry-run=client -o yaml" to false,
            "$kubeconfig kubectl apply -f $path" to true,
            "rm -f $path" to false,
        )
    }

    @Test
    fun `wait names the condition, object, namespace and timeout`() {
        kubectl.wait("Deployment", "myapp", "Available", "mynamespace", "120s")

        assertThat(commands).containsExactly(
            "$kubeconfig kubectl wait --for=condition=Available Deployment/myapp -n mynamespace --timeout 120s" to true,
        )
    }

    @Test
    fun `delete ignores a missing object only when asked to`() {
        kubectl.delete("Pod", "mypod", "default", ignoreNotFound = true)
        kubectl.delete("Pod", "mypod", "default", ignoreNotFound = false)

        assertThat(commands).containsExactly(
            "$kubeconfig kubectl delete Pod/mypod -n default --ignore-not-found" to true,
            "$kubeconfig kubectl delete Pod/mypod -n default" to true,
        )
    }

    @Test
    fun `listInAllNamespaces asks quietly for object names`() {
        kubectl.listInAllNamespaces("clusters.postgresql.cnpg.io")

        assertThat(commands).containsExactly("$kubeconfig kubectl get clusters.postgresql.cnpg.io --all-namespaces -o name" to false)
    }

    @Test
    fun `deleteBySelector quotes the selector and each name the lookup found`() {
        answers["$kubeconfig kubectl get deployment,pod -l 'easydblab/kit=memcached' -n default -o name"] =
            "deployment.apps/memcached\npod/memcached-abc\n"

        kubectl.deleteBySelector(listOf("deployment", "pod"), "easydblab/kit=memcached", "default")

        assertThat(commands).containsExactly(
            "$kubeconfig kubectl get deployment,pod -l 'easydblab/kit=memcached' -n default -o name" to false,
            "$kubeconfig kubectl delete 'deployment.apps/memcached' 'pod/memcached-abc' -n default --ignore-not-found" to true,
        )
    }

    @Test
    fun `deleteBySelector quotes a selector the shell would otherwise split`() {
        kubectl.deleteBySelector(listOf("pod"), "tier in (web, cache)", "default")

        assertThat(commands).containsExactly("$kubeconfig kubectl get pod -l 'tier in (web, cache)' -n default -o name" to false)
    }

    @Test
    fun `repoAdd adds the repository with --force-update, then updates it`() {
        helm.repoAdd("myrepo", "https://example.com/charts")

        assertThat(commands).containsExactly(
            "$kubeconfig helm repo add myrepo https://example.com/charts --force-update" to true,
            "$kubeconfig helm repo update myrepo" to true,
        )
    }

    @Test
    fun `upgradeInstall renders every option in order`() {
        helm.upgradeInstall(
            release = "myapp",
            chart = "myrepo/myapp",
            namespace = "mynamespace",
            version = "1.2.3",
            values = linkedMapOf("key1" to "val1", "key2" to "val2"),
            createNamespace = true,
            wait = true,
            timeout = "10m",
        )

        assertThat(commands).containsExactly(
            "$kubeconfig helm upgrade --install myapp myrepo/myapp --namespace mynamespace --create-namespace " +
                "--version 1.2.3 --set key1=val1 --set key2=val2 --wait --timeout 10m" to true,
        )
    }

    @Test
    fun `upgradeInstall with a values file uploads it, installs with the uploaded path, then removes it`() {
        val valuesFile = File(tempDir, "values.yaml").also { it.writeText("key: value") }

        helm.upgradeInstall(release = "myapp", chart = "myrepo/myapp", namespace = "default", valuesFile = valuesFile)

        val path = uploadedPath()
        assertThat(path).matches("/tmp/easydblab-input-[0-9a-f-]{36}\\.yaml")
        assertThat(commands).containsExactly(
            "$kubeconfig helm upgrade --install myapp myrepo/myapp --namespace default --values $path" to true,
            "rm -f $path" to false,
        )
    }

    @Test
    fun `uninstall ignores a release that is not there`() {
        helm.uninstall("myapp", "default")

        assertThat(commands).containsExactly("$kubeconfig helm uninstall myapp -n default --ignore-not-found" to true)
    }

    @Test
    fun `releaseExists quotes the filter so the remote shell does not read the dollar sign`() {
        helm.releaseExists("myapp", "default")

        assertThat(commands).containsExactly("$kubeconfig helm list -n default -f '^myapp\$' -q" to false)
    }

    /**
     * A kit variable is interpolated into step fields before they reach these services, so any value
     * can hold a space or a shell metacharacter, and an unset one is left as a literal `${NAME}`. Each call is run as the real remote command through
     * `/bin/sh`, with helm and kubectl defined as shell functions that print the words they were given:
     * the remote shell must hand the tool exactly the arguments the local form would pass as a list,
     * the `;` must never end the command and run the rest, and the literal `${NAME}` must not be expanded.
     */
    @Test
    fun `a kit variable with a space and a semicolon reaches the remote tool as one argument and runs nothing`() {
        val marker = File(tempDir, "injected")
        val value = "a value; touch ${marker.absolutePath} \${UNSET_KIT_VARIABLE}"
        val recorded = RecordingClusterTooling()
        val calls: List<(KubectlService, HelmService) -> Unit> =
            listOf(
                { _, h -> h.repoAdd(value, "https://example.com/charts?$value") },
                { _, h -> h.upgradeInstall(value, value, value, version = value, values = mapOf("key" to value)) },
                { _, h -> h.uninstall(value, value) },
                { _, h -> h.releaseExists(value, value) },
                { k, _ -> k.applyUrl(value) },
                { k, _ -> k.applyKustomize(value) },
                { k, _ -> k.wait(value, value, value, value, value) },
                { k, _ -> k.delete(value, value, value, ignoreNotFound = true) },
                { k, _ -> k.listInAllNamespaces(value) },
            )

        calls.forEach { call ->
            call(kubectl, helm)
            call(DefaultKubectlService(recorded), DefaultHelmService(recorded))
        }

        assertThat(commands.map { (command, _) -> wordsTheRemoteToolSees(command) })
            .containsExactlyElementsOf(recorded.calls.map { listOf(it.tool.executable) + it.args })
        assertThat(marker).doesNotExist()
    }

    /** The tool name and arguments [command] gives helm or kubectl when a POSIX shell runs it. */
    private fun wordsTheRemoteToolSees(command: String): List<String> {
        val printArgs = "for word; do printf '%s\\0' \"\$word\"; done"
        val script = "helm() ( set -- helm \"\$@\"; $printArgs ); kubectl() ( set -- kubectl \"\$@\"; $printArgs ); $command"
        // In the temp directory, so a regression that runs the injected command cannot write into the checkout.
        val process = ProcessBuilder("/bin/sh", "-c", script).directory(tempDir).redirectErrorStream(true).start()
        val printed = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        return printed.split('\u0000').dropLast(1)
    }
}
