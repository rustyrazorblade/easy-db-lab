package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.KitArgSpec
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.TemplateVariables
import com.rustyrazorblade.easydblab.services.installConfigYaml
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.Deployment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * The SingleStore kit's contracts that span its files: the MemsqlCluster the start step applies,
 * the Secrets it names, the NodePort, the scrape and the runtime selector against the pod labels,
 * the args and their install-time checks, the rendered `kit.yaml`, the stop and uninstall order,
 * and the database SQL for both `--unlimited-storage` settings.
 */
class SingleStoreKitTest : BaseKoinTest() {
    private val templateService by lazy { TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()) }
    private val kit by lazy { BuiltinKitFixture("singlestore", templateService) }

    private fun cluster(args: Map<String, String> = emptyMap()): GenericKubernetesResource =
        kit.render("memsqlcluster.yaml.template", args).filterIsInstance<GenericKubernetesResource>().single()

    private fun podLabels(spec: String): Map<String, String> = cluster().get("spec", spec, "objectMetaOverrides", "labels")

    private fun arg(flag: String): KitArgSpec = kit.config.args.single { it.flag == flag }

    @Test
    fun `the operator runs the pinned image with the upstream args on the control node`() {
        val operator = kit.render("operator-deployment.yaml.template").filterIsInstance<Deployment>().single()
        val pod = operator.spec.template.spec
        val container = pod.containers.single()

        assertThat(container.image).isEqualTo("singlestore/operator:4.252.0-3adf6c3d")
        assertThat(container.args).containsExactly(
            "--merge-service-annotations",
            "--disable-finalizer",
            "--fs-group-id",
            "5555",
            "--cluster-id",
            "singlestore",
        )
        assertThat(pod.nodeSelector).containsEntry("node-role.kubernetes.io/control-plane", "true")
        assertThat(pod.tolerations.map { it.key }).contains("node-role.kubernetes.io/control-plane")
        assertThat(pod.serviceAccountName).isEqualTo("singlestore-operator")
    }

    @Test
    fun `--operator-version selects the operator image tag`() {
        val operator =
            kit
                .render("operator-deployment.yaml.template", mapOf(arg("--operator-version").variable to "4.999.0-test"))
                .filterIsInstance<Deployment>()
                .single()

        assertThat(
            operator.spec.template.spec.containers
                .single()
                .image,
        ).isEqualTo("singlestore/operator:4.999.0-test")
    }

    @Test
    fun `install applies the packaged CRD, RBAC and operator, and no chart or URL`() {
        val manifests =
            kit.config.install
                .filterIsInstance<InstallStep.Manifest>()
                .map { it.template }

        assertThat(manifests).containsExactly("crd.yaml", "operator-rbac.yaml", "operator-deployment.yaml")
        assertThat(kit.config.install).noneMatch { it is InstallStep.Helm || it is InstallStep.ManifestUrl }
        assertThat(kit.resource("crd.yaml")).contains("name: memsqlclusters.memsql.com", "x-kubernetes-preserve-unknown-fields: true")
    }

    @Test
    fun `the cluster names the Secrets the start step writes`() {
        val cr = cluster()
        val startStep = kit.resource("bin/lib/singlestore-cluster.sh")

        assertThat(
            cr.get<Map<String, String>>("spec", "licenseSecret"),
        ).isEqualTo(mapOf("name" to "singlestore-license", "key" to "license"))
        assertThat(cr.get<Map<String, String>>("spec", "rootPasswordSecret"))
            .isEqualTo(mapOf("name" to "singlestore-root-password", "key" to "ROOT_PASSWORD"))
        assertThat(
            startStep,
        ).contains("ss_apply_secret singlestore-license license ", "ss_apply_secret singlestore-root-password ROOT_PASSWORD ")
    }

    @Test
    fun `the cluster runs the node image from --version on db nodes with local-path storage and a ClusterIP service`() {
        val cr = cluster(mapOf(arg("--version").variable to "alma-9.9.9-test"))

        assertThat(cr.get<String>("spec", "nodeImage", "tag")).isEqualTo("alma-9.9.9-test")
        assertThat(cr.get<String>("spec", "nodeImage", "repository")).isEqualTo("singlestore/node")
        assertThat(cr.get<String>("spec", "serviceSpec", "type")).isEqualTo("ClusterIP")
        assertThat(listOf("masterAggregatorSpec", "aggregatorSpec", "leafSpec"))
            .allSatisfy { assertThat(cr.get<String>("spec", it, "storageClass")).isEqualTo("local-path") }
        val scheduling = cr.get<Map<String, Map<String, Any>>>("spec", "schedulingDetails")
        assertThat(scheduling.keys).containsExactlyInAnyOrder("master", "aggregator", "leaf-ag1", "leaf-ag2")
        assertThat(scheduling.values).allSatisfy { assertThat(it["nodeSelector"]).isEqualTo(mapOf("type" to "db")) }
    }

    /** The labels the required pod anti-affinity of scheduling group [group] repels, per db node. */
    private fun repelledLabels(group: String): Map<String, String> {
        val term =
            cluster()
                .get<List<Map<String, Any>>>(
                    "spec",
                    "schedulingDetails",
                    group,
                    "affinity",
                    "podAntiAffinity",
                    "requiredDuringSchedulingIgnoredDuringExecution",
                ).single()
        assertThat(term["topologyKey"]).isEqualTo("kubernetes.io/hostname")
        val matchLabels = (term["labelSelector"] as Map<*, *>)["matchLabels"] as Map<*, *>
        return matchLabels.entries.associate { (key, value) -> key.toString() to value.toString() }
    }

    @Test
    fun `every leaf availability group repels every leaf, so each leaf has its own db node`() {
        for (group in listOf("leaf-ag1", "leaf-ag2")) {
            val repelled = repelledLabels(group)
            assertThat(podLabels("leafSpec")).containsAllEntriesOf(repelled)
            assertThat(podLabels("masterAggregatorSpec")).doesNotContainEntry("easydblab/role", "leaf")
        }
    }

    @Test
    fun `the master and the child aggregators repel every aggregator, so a db node holds at most one`() {
        for (group in listOf("master", "aggregator")) {
            val repelled = repelledLabels(group)
            assertThat(repelled).containsEntry("easydblab/tier", "aggregator")
            assertThat(podLabels("masterAggregatorSpec")).containsAllEntriesOf(repelled)
            assertThat(podLabels("aggregatorSpec")).containsAllEntriesOf(repelled)
            assertThat(podLabels("leafSpec")).doesNotContainKey("easydblab/tier")
        }
    }

    @Test
    fun `pods are labelled by kit and role, and only aggregators carry the aggregator tier`() {
        assertThat(podLabels("masterAggregatorSpec"))
            .containsEntry("easydblab/kit", "singlestore")
            .containsEntry("easydblab/role", "master")
            .containsEntry("easydblab/tier", "aggregator")
        assertThat(podLabels("aggregatorSpec"))
            .containsEntry("easydblab/kit", "singlestore")
            .containsEntry("easydblab/role", "aggregator")
            .containsEntry("easydblab/tier", "aggregator")
        assertThat(podLabels("leafSpec"))
            .containsEntry("easydblab/kit", "singlestore")
            .containsEntry("easydblab/role", "leaf")
            .doesNotContainKey("easydblab/tier")
    }

    @Test
    fun `the aggregator args size the master and the child aggregators`() {
        val cr =
            cluster(
                mapOf(
                    arg("--aggregators").variable to "2",
                    arg("--aggregator-cores").variable to "3",
                ),
            )

        assertThat(cr.get<Int>("spec", "masterAggregatorSpec", "count")).isEqualTo(1)
        assertThat(cr.get<Int>("spec", "aggregatorSpec", "count")).isEqualTo(2)
        assertThat(cr.get<Int>("spec", "masterAggregatorSpec", "cores")).isEqualTo(3)
        assertThat(cr.get<Int>("spec", "aggregatorSpec", "cores")).isEqualTo(3)
    }

    @Test
    fun `the start step fills in exactly the run-time values the cluster leaves for it`() {
        val placeholders =
            Regex("""\$\{([A-Z_]+)}""")
                .findAll(
                    kit
                        .resource("memsqlcluster.yaml.template")
                        .lines()
                        .filterNot { it.trimStart().startsWith("#") }
                        .joinToString("\n"),
                ).map { it.groupValues[1] }
                .toSet()
        val filled =
            Regex("""SS_RUNTIME_VALUES="([^"]+)"""")
                .find(kit.resource("bin/lib/singlestore-cluster.sh"))
                ?.groupValues
                ?.get(1)
                .orEmpty()
                .split(" ")
                .toSet()

        assertThat(
            placeholders,
        ).containsExactlyInAnyOrder("REDUNDANCY", "LEAF_COUNT", "LEAF_CORES", "LEAF_MEMORY_MB", "STORAGE_GB", "AGGREGATOR_MEMORY_MB")
        assertThat(filled).isEqualTo(placeholders)
    }

    @Test
    fun `the NodePort forwards 30306 to 3306 on every aggregator and no leaf`() {
        val service = kit.render("nodeport-service.yaml").filterIsInstance<Service>().single()
        val port = service.spec.ports.single()

        assertThat(service.spec.type).isEqualTo("NodePort")
        assertThat(port.nodePort).isEqualTo(30306)
        assertThat(port.targetPort.intVal).isEqualTo(3306)
        assertThat(service.spec.selector).containsEntry("easydblab/tier", "aggregator")
        assertThat(podLabels("masterAggregatorSpec")).containsAllEntriesOf(service.spec.selector)
        assertThat(podLabels("aggregatorSpec")).containsAllEntriesOf(service.spec.selector)
        assertThat(podLabels("leafSpec").entries.containsAll(service.spec.selector.entries)).isFalse()
        assertThat(
            kit.config.stop
                .filterIsInstance<InstallStep.Delete>()
                .map { it.name },
        ).contains(service.metadata.name)
    }

    @Test
    fun `both endpoints are on the NodePort, on db nodes`() {
        val endpoints = kit.config.endpoints

        assertThat(endpoints.map { it.type }).containsExactlyInAnyOrder(KitEndpoint.EndpointType.MYSQL, KitEndpoint.EndpointType.JDBC)
        assertThat(endpoints).allSatisfy {
            assertThat(it.port).isEqualTo(30306)
            assertThat(it.nodeType).isEqualTo("db")
        }
        assertThat(endpoints.single { it.type == KitEndpoint.EndpointType.JDBC }.scheme).isEqualTo("mysql")
    }

    @Test
    fun `the exporter sidecar of the master pod is scraped and nothing else`() {
        val scrape = kit.scrapeMetrics.single()
        val selector = parseLabelSelector(scrape.podSelector)

        assertThat(scrape.port).isEqualTo(9104)
        assertThat(scrape.path).isEqualTo("/cluster-metrics")
        assertThat(podLabels("masterAggregatorSpec")).containsAllEntriesOf(selector)
        assertThat(podLabels("aggregatorSpec").entries.containsAll(selector.entries)).isFalse()
        assertThat(podLabels("leafSpec").entries.containsAll(selector.entries)).isFalse()
        assertThat(kit.resource("kit.yaml")).doesNotContain("exporter_port")
    }

    @Test
    fun `the runtime selector matches every database pod and not the operator`() {
        val runtime = parseLabelSelector(requireNotNull(kit.config.runtime).selector)
        val operator = kit.render("operator-deployment.yaml.template").filterIsInstance<Deployment>().single()

        assertThat(listOf("masterAggregatorSpec", "aggregatorSpec", "leafSpec"))
            .allSatisfy { assertThat(podLabels(it)).containsAllEntriesOf(runtime) }
        assertThat(
            operator.spec.template.metadata.labels.entries
                .containsAll(runtime.entries),
        ).isFalse()
    }

    @Test
    fun `every arg is a named option with its default`() {
        val defaults = kit.config.args.associate { it.flag to it.default }

        assertThat(defaults).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "--license" to "",
                "--version" to "alma-9.0.44-1322a9e639",
                "--operator-version" to "4.252.0-3adf6c3d",
                "--aggregators" to "0",
                "--leaves" to "\${DB_NODE_COUNT}",
                "--redundancy" to "",
                "--size" to "10Ti",
                "--leaf-cores" to "",
                "--leaf-memory" to "",
                "--aggregator-cores" to "2",
                "--aggregator-memory" to "8",
                "--password" to "easydblab",
                "--database" to "test",
                "--unlimited-storage" to "false",
                "--blob-cache" to "",
            ),
        )
        assertThat(arg("--license").required).isTrue()
        assertThat(
            kit.config.args
                .filter { it.required }
                .map { it.flag },
        ).containsExactly("--license")
        assertThat(arg("--unlimited-storage").type).isEqualTo(KitArgSpec.ArgType.BOOLEAN)
    }

    @Test
    fun `--database takes only letters, digits and underscores`() {
        val database = arg("--database")

        assertThat(listOf("test", "bench_1", "ABC")).allSatisfy { assertThat(database.accepts(it)).isTrue() }
        assertThat(listOf("my-db", "a b", "x;drop", "")).allSatisfy { assertThat(database.accepts(it)).isFalse() }
    }

    @Test
    fun `--password refuses a double quote, a backslash and a control character, and nothing else`() {
        val password = arg("--password")

        assertThat(listOf("easydblab", "p w*'d", "s!ngle\$tore#1")).allSatisfy { assertThat(password.accepts(it)).isTrue() }
        assertThat(listOf("a\"b", "a\\b", "a\tb", "a\nb", "")).allSatisfy { assertThat(password.accepts(it)).isFalse() }
    }

    /** The installed `kit.yaml` as `kit install` renders it with the defaults overlaid by [args]. */
    private fun installedKitYaml(args: Map<String, String>): KitConfig {
        val defaults = kit.config.args.associate { it.variable to it.default }
        val rendered =
            templateService.renderKitTemplate(
                templateContent = kit.resource("kit.yaml"),
                vars =
                    TemplateVariables.from(
                        state = ClusterState(name = "c", versions = mutableMapOf()),
                        kitName = "singlestore",
                        storageSize = "10Ti",
                    ),
                extraVars = defaults + args,
            )
        return installConfigYaml.decodeFromString(KitConfig.serializer(), rendered)
    }

    @Test
    fun `the installed kit yaml carries the installed password and database`() {
        val installed = installedKitYaml(mapOf(arg("--password").variable to "X y*'z", arg("--database").variable to "bench"))
        val sql = installed.capabilities.single { it.type == "sql" }

        assertThat(sql.user).isEqualTo("root")
        assertThat(sql.password).isEqualTo("X y*'z")
        assertThat(sql.driverClass).isEqualTo("com.mysql.cj.jdbc.Driver")
        assertThat(installed.endpoints.single { it.type == KitEndpoint.EndpointType.MYSQL }.database).isEqualTo("bench")
        assertThat(installed.endpoints.single { it.type == KitEndpoint.EndpointType.JDBC }.formatUrl("10.0.0.1"))
            .startsWith("jdbc:mysql://10.0.0.1:30306/bench?")
    }

    @ParameterizedTest
    @ValueSource(strings = ["a__REGION__b", "a\$__X__b", "p__KIT_NAME__w"])
    fun `the installed kit yaml carries a password holding a placeholder exactly as given`(password: String) {
        val installed = installedKitYaml(mapOf(arg("--password").variable to password))

        assertThat(installed.capabilities.single { it.type == "sql" }.password).isEqualTo(password)
    }

    @Test
    fun `the installed kit yaml carries a database named like a placeholder exactly as given`() {
        val installed = installedKitYaml(mapOf(arg("--database").variable to "__CLUSTER_NAME__"))

        assertThat(installed.endpoints.map { it.database }).containsOnly("__CLUSTER_NAME__")
    }

    @Test
    fun `the installed kit yaml defaults to the easydblab password and the test database`() {
        val installed = installedKitYaml(emptyMap())

        assertThat(installed.capabilities.single().password).isEqualTo("easydblab")
        assertThat(installed.endpoints.map { it.database }).containsOnly("test")
    }

    @Test
    fun `stop removes the cluster, the NodePort, the cluster's PVCs and then the Secrets`() {
        val deletes = kit.config.stop.filterIsInstance<InstallStep.Delete>()

        assertThat(deletes.filterNot { it.bySelector }.map { it.kind to it.name })
            .containsExactly(
                "MemsqlCluster" to "singlestore",
                "Service" to "singlestore-nodeport",
                "Secret" to "singlestore-license",
                "Secret" to "singlestore-root-password",
            )
        assertThat(deletes.single { it.bySelector }.let { it.kinds to it.selector })
            .isEqualTo(listOf("PersistentVolumeClaim") to "app.kubernetes.io/instance=singlestore")
    }

    @Test
    fun `uninstall removes a leftover cluster, then the operator and the Secrets, and the CRD last`() {
        val deletes = kit.config.uninstall.map { it as InstallStep.Delete }
        val kinds = deletes.map { it.kind }
        val secrets = deletes.filter { it.kind == "Secret" }

        assertThat(kinds.first()).isEqualTo("MemsqlCluster")
        assertThat(kinds.last()).isEqualTo("CustomResourceDefinition")
        assertThat(kinds.indexOf("Deployment")).isLessThan(kinds.indexOf("CustomResourceDefinition"))
        assertThat(kinds).contains("Role", "RoleBinding", "ServiceAccount", "ClusterRole", "ClusterRoleBinding")
        assertThat(deletes.single { it.kind == "CustomResourceDefinition" }.name).isEqualTo("memsqlclusters.memsql.com")
        assertThat(secrets.map { it.name to it.namespace })
            .containsExactlyInAnyOrder("singlestore-license" to "default", "singlestore-root-password" to "default")
        assertThat(secrets.map { deletes.indexOf(it) }).allSatisfy {
            assertThat(it).isGreaterThan(kinds.indexOf("Deployment")).isLessThan(kinds.indexOf("CustomResourceDefinition"))
        }
    }
}
