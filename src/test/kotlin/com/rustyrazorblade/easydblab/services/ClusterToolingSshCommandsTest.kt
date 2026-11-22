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
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Each [KubectlService] and [HelmService] method run through the real [SshClusterTooling] against a
 * mocked [RemoteOperationsService], asserting the exact remote commands, in order, whether each
 * one's output is shown, and the input sent as stdin. These are what a kit step runs on the control
 * node.
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

    /** The stdin of each command run with input, in order. */
    private val inputs = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        val stateManager = ClusterStateManager(File(stateDir, "state.json"))
        stateManager.replace(
            ClusterState(
                name = "test",
                hosts = mapOf(ServerType.Control to listOf(ClusterHost("54.1.2.3", "10.0.0.1", "control0", "us-west-2a"))),
            ),
        )
        whenever(remoteOps.executeRemotely(any(), any(), any(), any())).doAnswer {
            val command = it.getArgument<String>(1)
            commands += command to it.getArgument<Boolean>(2)
            Response(answers[command].orEmpty())
        }
        whenever(remoteOps.executeRemotelyWithInput(any(), any(), any(), any())).doAnswer {
            commands += it.getArgument<String>(1) to it.getArgument<Boolean>(3)
            inputs += it.getArgument<String>(2)
            Response("")
        }
        val tooling = SshClusterTooling(remoteOps, stateManager)
        kubectl = DefaultKubectlService(tooling)
        helm = DefaultHelmService(tooling)
    }

    /** One service call, the remote commands it must run, and the stdin of those run with input. */
    private data class Case(
        val name: String,
        val call: (KubectlService, HelmService) -> Unit,
        val expected: List<Pair<String, Boolean>>,
        val expectedInputs: List<String> = emptyList(),
    )

    @Test
    fun `each service method runs exactly its remote commands`() {
        val k = kubeconfig
        val valuesFile = File(tempDir, "values.yaml").also { it.writeText("key: value") }
        answers["$k kubectl get deployment,pod -l easydblab/kit=memcached -n default -o name"] =
            "deployment.apps/memcached\npod/memcached-abc\n"
        val cases =
            listOf(
                Case(
                    "applyUrl",
                    { kc, _ -> kc.applyUrl("https://example.com/m.yaml") },
                    listOf(
                        "$k kubectl apply --server-side -f https://example.com/m.yaml" to true,
                    ),
                ),
                Case(
                    "applyKustomize",
                    { kc, _ -> kc.applyKustomize("https://example.com/k") },
                    listOf(
                        "$k kubectl apply -k https://example.com/k" to true,
                    ),
                ),
                Case(
                    "applyContent",
                    { kc, _ -> kc.applyContent("kind: ConfigMap") },
                    listOf("$k kubectl apply -f -" to true),
                    listOf("kind: ConfigMap"),
                ),
                Case(
                    "createNamespace",
                    { kc, _ -> kc.createNamespace("cache") },
                    // One round trip: the manifest is the command's stdin, with no upload and no cleanup.
                    listOf("$k kubectl apply -f -" to true),
                    listOf("---\napiVersion: \"v1\"\nkind: \"Namespace\"\nmetadata:\n  name: \"cache\"\n"),
                ),
                Case(
                    "wait",
                    { kc, _ -> kc.wait("Deployment", "myapp", "Available", "ns", "120s") },
                    listOf("$k kubectl wait --for=condition=Available Deployment/myapp -n ns --timeout 120s" to true),
                ),
                Case(
                    "delete",
                    { kc, _ -> kc.delete("Pod", "p", "default", ignoreNotFound = true) },
                    listOf(
                        "$k kubectl delete Pod/p -n default --ignore-not-found" to true,
                    ),
                ),
                Case(
                    "delete, missing not ignored",
                    { kc, _ -> kc.delete("Pod", "p", "default", ignoreNotFound = false) },
                    listOf(
                        "$k kubectl delete Pod/p -n default" to true,
                    ),
                ),
                Case(
                    "listInAllNamespaces",
                    { kc, _ -> kc.listInAllNamespaces("clusters.postgresql.cnpg.io") },
                    listOf("$k kubectl get clusters.postgresql.cnpg.io --all-namespaces -o name" to false),
                ),
                Case(
                    "deleteBySelector",
                    { kc, _ -> kc.deleteBySelector(listOf("deployment", "pod"), "easydblab/kit=memcached", "default") },
                    listOf(
                        "$k kubectl get deployment,pod -l easydblab/kit=memcached -n default -o name" to false,
                        "$k kubectl delete deployment.apps/memcached pod/memcached-abc -n default --ignore-not-found" to true,
                    ),
                ),
                Case(
                    "deleteBySelector, set-based selector",
                    { kc, _ -> kc.deleteBySelector(listOf("pod"), "tier in (web, cache)", "default") },
                    listOf("$k kubectl get pod -l 'tier in (web, cache)' -n default -o name" to false),
                ),
                Case(
                    "repoAdd",
                    { _, h -> h.repoAdd("myrepo", "https://example.com/charts") },
                    listOf(
                        "$k helm repo add myrepo https://example.com/charts --force-update" to true,
                        "$k helm repo update myrepo" to true,
                    ),
                ),
                Case(
                    "upgradeInstall, every option",
                    { _, h ->
                        h.upgradeInstall(
                            release = "myapp",
                            chart = "myrepo/myapp",
                            namespace = "ns",
                            version = "1.2.3",
                            values = linkedMapOf("key1" to "val1", "key2" to "val2"),
                            createNamespace = true,
                            wait = true,
                            timeout = "10m",
                        )
                    },
                    listOf(
                        "$k helm upgrade --install myapp myrepo/myapp --namespace ns --create-namespace --version 1.2.3 " +
                            "--set key1=val1 --set key2=val2 --wait --timeout 10m" to true,
                    ),
                ),
                Case(
                    "upgradeInstall, values file",
                    { _, h -> h.upgradeInstall(release = "myapp", chart = "myrepo/myapp", namespace = "default", valuesFile = valuesFile) },
                    listOf("$k helm upgrade --install myapp myrepo/myapp --namespace default --values -" to true),
                    listOf("key: value"),
                ),
                Case(
                    "uninstall",
                    { _, h -> h.uninstall("myapp", "default") },
                    listOf(
                        "$k helm uninstall myapp -n default --ignore-not-found" to true,
                    ),
                ),
                Case(
                    "releaseExists",
                    { _, h -> h.releaseExists("myapp", "default") },
                    listOf(
                        "$k helm list -n default -f '^myapp\$' -q" to false,
                    ),
                ),
            )

        cases.forEach { case ->
            commands.clear()
            inputs.clear()

            case.call(kubectl, helm)

            assertThat(commands).describedAs(case.name).containsExactlyElementsOf(case.expected)
            assertThat(inputs).describedAs("${case.name} input").containsExactlyElementsOf(case.expectedInputs)
        }
    }

    /**
     * A kit variable is interpolated into step fields before they reach these services, so any value
     * can hold a space or a shell metacharacter, and an unset one is left as a literal `${NAME}`. Each
     * call is run as the real remote command through `/bin/sh`, with helm and kubectl defined as shell
     * functions that print the words they were given: the remote shell must hand the tool exactly the
     * argument list the service built, the `;` must never end the command and run the rest, and the
     * literal `${NAME}` must not be expanded.
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
                { k, _ -> k.deleteBySelector(listOf(value), value, value) },
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
