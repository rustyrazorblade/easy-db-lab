package com.rustyrazorblade.easydblab.localstack

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorTaskDefinition
import com.rustyrazorblade.easydblab.configuration.grafana.BackendUrls
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardProvisioningConfig
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDatasourceSet
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaServerSettings
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The live local stack, rendered from a spec and read back: its structure, its images, the read-only
 * component flags, the gate, signer and store check wiring, and Grafana's datasources.
 */
class LocalStackComposerTest {
    private val live = LocalStackSpec.Mode.Live(bucket = "acct-bucket", region = "eu-west-1", awsProfile = "lab", home = "default")
    private val spec = LocalStackSpec(mode = live, tenants = listOf("acme", "default"), grafanaPort = 3300, documentsPort = 3380)

    private val files = LocalStackComposer.compose(spec)
    private val compose = ComposeFile.fromYaml(text(Constants.LocalStack.COMPOSE_FILE))

    private fun text(path: String): String = String(checkNotNull(files[path]) { "no $path among ${files.keys}" })

    private fun service(name: String): ComposeService = checkNotNull(compose.services[name]) { "no service $name" }

    /** The content of the file [service] sees at [path], through the read-only configuration directory mount that holds it. */
    private fun mountedFile(
        service: String,
        path: String,
    ): String {
        val mount = service(service).volumes.orEmpty().single { it.source.startsWith("./config/") && path.startsWith("${it.target}/") }
        assertThat(mount.type).isEqualTo("bind")
        assertThat(mount.readOnly).isTrue()
        return text(mount.source.removePrefix("./") + path.removePrefix(mount.target))
    }

    /** The configuration file nginx in [service] reads: the one its command names with `-c`. */
    private fun nginxConfig(service: String): String {
        val command = service(service).command.orEmpty()
        return command[command.indexOf("-c") + 1]
    }

    /**
     * The tool rewrites a file by renaming a new one over it. A container that bind-mounts the file
     * itself keeps reading the old one, so Grafana's provisioning reload failed with "no such file";
     * a mounted directory shows the renamed file.
     */
    @Test
    fun `every configuration is mounted as its directory, so a running container reads a rewritten file`() {
        val configMounts =
            compose.services.values
                .flatMap { it.volumes.orEmpty() }
                .filter { it.source.startsWith("./config") }

        assertThat(configMounts).isNotEmpty().allSatisfy { mount ->
            val directory = mount.source.removePrefix("./")
            assertThat(files.keys).describedAs(directory).doesNotContain(directory).anyMatch { it.startsWith("$directory/") }
        }
    }

    private fun resource(
        anchor: Class<*>,
        name: String,
    ) = CompactorTaskDefinition.resource(anchor, name)

    @Test
    fun `the stack is Mimir with memcached, Loki, Tempo, Grafana, the documents view, the gate, the signer and the store check`() {
        assertThat(compose.services.keys).containsExactlyInAnyOrder(
            "mimir",
            "memcached",
            "loki",
            "tempo",
            "grafana",
            "documents-web",
            "store-gate",
            "store-signer",
            "store-check",
        )
    }

    @Test
    fun `every image is its component's registered pin`() {
        val expected =
            mapOf(
                "mimir" to MimirManifestBuilder.IMAGE,
                "memcached" to Constants.K8s.MIMIR_MEMCACHED_IMAGE,
                "loki" to LokiManifestBuilder.IMAGE,
                "tempo" to TempoManifestBuilder.IMAGE,
                "grafana" to LocalImages.GRAFANA,
                "documents-web" to Constants.Grafana.Documents.WEB_SERVER_IMAGE,
                "store-signer" to Constants.Grafana.Documents.SIGV4_PROXY_IMAGE,
                "store-gate" to LocalImages.GATE,
                "store-check" to LocalImages.GATE,
            )
        val images = compose.services.mapValues { it.value.image }

        assertThat(images).containsExactlyInAnyOrderEntriesOf(expected)
    }

    @Test
    fun `no image of the stack is unregistered or unpinned`() {
        val mountedImages =
            compose.services.values
                .flatMap { it.volumes.orEmpty() }
                .filter { it.type == "image" }
                .map { it.source }
        val every = compose.services.values.map { it.image } + mountedImages

        assertThat(every).allSatisfy { image ->
            assertThat(LocalImages.all).describedAs("registered pins").contains(image)
            assertThat(image).contains(":").doesNotEndWith(":latest")
        }
    }

    @Test
    fun `Mimir runs the read path with an idle ingester, through the gate, with no compactor`() {
        val command = service("mimir").command.orEmpty()

        assertThat(command).contains(
            "-config.file=/etc/mimir/mimir.yaml",
            "-config.expand-env=true",
            "-target=query-frontend,querier,store-gateway,query-scheduler,ingester",
            "-usage-stats.enabled=false",
            "-blocks-storage.s3.endpoint=store-gate:8080",
            "-blocks-storage.s3.insecure=true",
            "-blocks-storage.s3.bucket-lookup-type=path",
        )
        assertThat(command.filter { it.startsWith("-target=") }.single()).doesNotContain("compactor", "distributor")
        assertThat(mountedFile("mimir", "/etc/mimir/mimir.yaml")).isEqualTo(resource(MimirManifestBuilder::class.java, "mimir.yaml"))
    }

    @Test
    fun `Mimir's four caches are the stack's memcached, which listens on the project network`() {
        assertThat(service("mimir").command).contains(
            "-blocks-storage.bucket-store.index-cache.memcached.addresses=memcached:11211",
            "-blocks-storage.bucket-store.chunks-cache.memcached.addresses=memcached:11211",
            "-blocks-storage.bucket-store.metadata-cache.memcached.addresses=memcached:11211",
            "-query-frontend.results-cache.memcached.addresses=memcached:11211",
        )
        val memcached = service("memcached")
        assertThat(memcached.command).containsSequence("-p", "11211")
        assertThat(memcached.command).doesNotContain("-l")
        assertThat(service("mimir").dependsOn).containsKey("memcached")
    }

    /** memcached's value of [flag], such as `-I 16m`, in bytes. */
    private fun memcachedSize(flag: String): Long {
        val command = service("memcached").command.orEmpty()
        val value = command[command.indexOf(flag) + 1]
        val units = mapOf('k' to (1L shl 10), 'm' to (1L shl 20), 'g' to (1L shl 30))
        return units[value.last()]?.let { value.dropLast(1).toLong() * it } ?: value.toLong()
    }

    /**
     * Tempo's trace-by-id fetch reads every page of a block, and only cached pages stop a repeated
     * fetch from reading the whole block again. memcached refuses an item over its `-I` limit, and at
     * the cluster's 1m it refused every 1 MiB read buffer Tempo stored.
     */
    @Test
    fun `Tempo reads through the stack's memcached, whose item limit holds the largest item Tempo stores`() {
        val tempo = Yaml.default.parseToYamlNode(mountedFile("tempo", "/etc/tempo/tempo.yaml")) as YamlMap
        val cache =
            tempo
                .get<YamlMap>("cache")
                ?.get<YamlList>("caches")
                ?.items
                ?.single() as YamlMap
        val client = checkNotNull(cache.get<YamlMap>("memcached"))
        val largestItem = checkNotNull(client.get<YamlScalar>("max_item_size")).toLong()

        assertThat(checkNotNull(client.get<YamlScalar>("addresses")).content).isEqualTo("memcached:11211")
        assertThat(memcachedSize("-I")).isGreaterThan(largestItem)
        assertThat(service("tempo").dependsOn).containsKey("memcached")
    }

    @Test
    fun `Loki runs the read path only, querying the store, with its own address as the compactor's`() {
        val command = service("loki").command.orEmpty()

        assertThat(command).contains(
            "-config.file=/etc/loki/loki.yaml",
            "-config.expand-env=true",
            "-target=read,query-scheduler",
            "-querier.query-store-only=true",
            "-common.compactor-address=http://127.0.0.1:3100",
            "-reporting.enabled=false",
            "-common.storage.s3.endpoint=store-gate:8080",
            "-common.storage.s3.insecure=true",
            "-common.storage.s3.force-path-style=true",
        )
        assertThat(mountedFile("loki", "/etc/loki/loki.yaml")).isEqualTo(resource(LokiManifestBuilder::class.java, "loki.yaml"))
    }

    /**
     * Loki's aws-sdk-go-v2 asks for checksum validation on every GetObject with an
     * `x-amz-checksum-mode` header, which the signer does not sign; S3 then refuses the request
     * ("headers present in the request which were not signed") and Loki reads nothing.
     */
    @Test
    fun `Loki sends a checksum header only when S3 requires one, so the signer signs every header it sends`() {
        assertThat(service("loki").environment).containsAllEntriesOf(
            mapOf(
                "AWS_RESPONSE_CHECKSUM_VALIDATION" to "when_required",
                "AWS_REQUEST_CHECKSUM_CALCULATION" to "when_required",
            ),
        )
    }

    @Test
    fun `Tempo is the single binary with the generated config`() {
        val command = service("tempo").command.orEmpty()

        assertThat(command).containsExactly("-config.file=/etc/tempo/tempo.yaml", "-config.expand-env=true")
        assertThat(mountedFile("tempo", "/etc/tempo/tempo.yaml"))
            .isEqualTo(
                TempoLocalConfig.live(resource(TempoManifestBuilder::class.java, "tempo.yaml"), "store-gate:8080", "memcached:11211"),
            )
    }

    @Test
    fun `the backends read the account bucket's prefixes, region and the home tenant from their environment`() {
        for (name in listOf("mimir", "loki", "tempo")) {
            assertThat(service(name).environment).describedAs(name).containsAllEntriesOf(
                mapOf(
                    "S3_BUCKET" to "acct-bucket",
                    "AWS_REGION" to "eu-west-1",
                    "METRICS_S3_PREFIX" to "mimir",
                    "LOGS_S3_PREFIX" to "loki",
                    "TRACES_S3_PREFIX" to "tempo",
                    "TENANT" to "default",
                ),
            )
        }
    }

    @Test
    fun `no backend starts until the store check read the bucket`() {
        for (name in listOf("mimir", "loki", "tempo")) {
            assertThat(service(name).dependsOn).describedAs(name).containsEntry("store-check", ComposeDependency.COMPLETED)
        }
        val check = service("store-check")
        assertThat(check.restart).isEqualTo("no")
        assertThat(check.dependsOn).containsKey("store-signer")
        assertThat(check.entrypoint).containsExactly("/bin/sh", "/store-check/store-check.sh")
        assertThat(check.environment).containsAllEntriesOf(
            mapOf("BUCKET" to "acct-bucket", "PROFILE" to "lab", "SIGNER" to "store-signer:8080"),
        )
        assertThat(mountedFile("store-check", "/store-check/store-check.sh"))
            .isEqualTo(resource(LocalStackComposer::class.java, "store-check.sh"))
    }

    @Test
    fun `the signer reads the operator's aws directory read-only, with the profile and HOME set`() {
        val signer = service("store-signer")

        assertThat(signer.environment).containsExactlyInAnyOrderEntriesOf(mapOf("HOME" to "/root", "AWS_PROFILE" to "lab"))
        assertThat(signer.volumes).contains(ComposeMount.bind("~/.aws", "/root/.aws", readOnly = true))
        assertThat(signer.command).containsSequence("--region", "eu-west-1")
        assertThat(signer.command).containsSequence("--host", "s3.eu-west-1.amazonaws.com")
        assertThat(signer.command).containsSequence("--port", ":8080")
        assertThat(signer.ports).isNull()
    }

    @Test
    fun `the signer turns unhealthy once it can no longer read the bucket`() {
        val signer = service("store-signer")
        val healthcheck = checkNotNull(signer.healthcheck)
        val tools = signer.volumes.orEmpty().single { it.type == "image" }

        assertThat(tools.source).isEqualTo(LocalImages.HEALTHCHECK_TOOLS)
        assertThat(healthcheck.test.first()).isEqualTo("CMD")
        assertThat(healthcheck.test[1]).startsWith(tools.target)
        assertThat(healthcheck.test.last()).startsWith("http://127.0.0.1:8080/acct-bucket")
        assertThat(healthcheck.interval).isEqualTo("60s")
    }

    @Test
    fun `the gate passes only GET and HEAD, query strings included, to the signer`() {
        val gate = mountedFile("store-gate", nginxConfig("store-gate"))

        assertThat(gate).contains("listen 8080;", "proxy_pass http://store-signer:8080;")
        assertThat(gate.replace(Regex("\\s+"), " ")).contains("limit_except GET { deny all; }")
        assertThat(gate).doesNotContain("__")
        assertThat(service("store-gate").ports).isNull()
    }

    @Test
    fun `every backend and the documents view reach S3 only through the gate`() {
        val documents = mountedFile("documents-web", nginxConfig("documents-web"))

        assertThat(documents).contains("proxy_pass http://store-gate:8080/acct-bucket/reports/;")
        for (name in listOf("mimir", "loki", "tempo", "grafana", "documents-web", "memcached")) {
            assertThat(service(name).environment.orEmpty().keys).describedAs(name).doesNotContain("AWS_PROFILE")
            assertThat(service(name).volumes.orEmpty().map { it.source }).describedAs(name).noneMatch { it.contains(".aws") }
        }
        assertThat(service("documents-web").ports).containsExactly("127.0.0.1:3380:3080")
    }

    /**
     * The signer signs any method with the operator's credentials. Grafana runs anonymous Admin, so a
     * datasource pointed at the signer would bypass the gate and could DELETE the owner's data: no
     * service but the gate and the store check may reach it.
     */
    @Test
    fun `only the gate and the store check share a network with the signer, whose way out is its own`() {
        assertThat(ComposeNetworks.sharingWith(compose, "store-signer")).isEqualTo(ComposeNetworks.SIGNER_PEERS)
        val egress = ComposeNetworks.of(compose, "store-signer") - ComposeNetworks.of(compose, "store-gate")
        assertThat(egress).describedAs("the signer's egress network").isNotEmpty()
        assertThat(compose.services.keys - "store-signer").allSatisfy { other ->
            assertThat(ComposeNetworks.of(compose, other)).describedAs(other).doesNotContainAnyElementsOf(egress)
        }
        val access = ComposeNetworks.of(compose, "store-signer") - egress
        assertThat(
            access.map { compose.networks.orEmpty()[it]?.internal },
        ).describedAs("the gate's network to the signer").containsOnly(true)
    }

    @Test
    fun `every backend, Grafana and the documents view reach the gate, never the signer`() {
        for (name in listOf("mimir", "loki", "tempo", "grafana", "documents-web", "memcached")) {
            assertThat(ComposeNetworks.sharingWith(compose, name)).describedAs(name).contains("store-gate").doesNotContain("store-signer")
        }
    }

    /** Defense in depth: Grafana's data source proxy reaches only the stack's own backends, whatever a datasource names. */
    @Test
    fun `Grafana's data source proxy is allowed only the stack's backends`() {
        val allowed = service("grafana").environment.orEmpty()["GF_SECURITY_DATA_SOURCE_PROXY_WHITELIST"]

        assertThat(allowed?.split(' ')).containsExactlyInAnyOrder("mimir:9009", "loki:3100", "tempo:3200", "pyroscope:4040")
    }

    @Test
    fun `Grafana's datasources are a cluster's, on the compose backends`() {
        val datasources = mountedFile("grafana", "/etc/grafana/provisioning/datasources/datasources.yaml")
        val tenants = TenantSet.of("default", listOf("acme"))

        assertThat(datasources).isEqualTo(GrafanaDatasourceSet.build(tenants, BackendUrls.LOCAL_COMPOSE).toYaml())
        val local = GrafanaDatasourceSet.build(tenants, BackendUrls.LOCAL_COMPOSE).datasources.map { it.uid to it.type }
        val cluster = GrafanaDatasourceSet.build(tenants, BackendUrls.CONTROL_NODE).datasources.map { it.uid to it.type }
        assertThat(local).isEqualTo(cluster)
        assertThat(local.map { it.first }).contains("mimir", "mimir-acme", "mimir-default", "mimir--all", "loki--all", "tempo--all")
    }

    @Test
    fun `Grafana runs with the shared server settings and sweeps the provisioned dashboard tree`() {
        val grafana = service("grafana")
        val tree = "${Constants.LocalStack.DASHBOARDS_MOUNT}/${Constants.LocalStack.DASHBOARDS_TREE}"
        val provider = mountedFile("grafana", "/etc/grafana/provisioning/dashboards/dashboards.yaml")

        assertThat(grafana.environment)
            .isEqualTo(
                GrafanaServerSettings(
                    appTitle = "easy-db-lab local (default)",
                    dashboardRoot = tree,
                    plugins = emptyList(),
                    dataProxyTimeoutSeconds = Constants.LocalStack.COLD_READ_TIMEOUT_SECONDS,
                    dataSourceProxyAllowList = BackendUrls.LOCAL_COMPOSE.hostPorts(),
                ).environment().toMap(),
            )
        assertThat(provider).isEqualTo(GrafanaDashboardProvisioningConfig.forDashboardTree(tree).toYaml())
        assertThat(grafana.ports).containsExactly("127.0.0.1:3300:3000")
        assertThat(grafana.volumes).contains(
            ComposeMount.bind("./dashboards", Constants.LocalStack.DASHBOARDS_MOUNT, readOnly = true),
            ComposeMount.bind("./grafana-data", "/var/lib/grafana", readOnly = false),
        )
    }

    /** A cold query over older data reads every block from S3 and outlasts Grafana's 30s default, which shows a 502. */
    @Test
    fun `Grafana waits five minutes for a datasource, as a cold local query can take minutes`() {
        assertThat(service("grafana").environment).containsEntry("GF_DATAPROXY_TIMEOUT", "300")
    }

    @Test
    fun `components keep their state under cache and Grafana under grafana-data, which the tool owns no file in`() {
        assertThat(service("mimir").volumes).contains(ComposeMount.bind("./cache/mimir", "/data", readOnly = false))
        assertThat(service("loki").volumes).contains(ComposeMount.bind("./cache/loki", "/loki", readOnly = false))
        assertThat(service("tempo").volumes).contains(ComposeMount.bind("./cache/tempo", "/var/tempo", readOnly = false))
        assertThat(files.keys).noneMatch { path ->
            Constants.LocalStack.PROTECTED_DIRS.any { path.startsWith("$it/") } || path == Constants.LocalStack.OVERRIDE_FILE
        }
    }

    @Test
    fun `nothing is published beyond Grafana and the documents view, and only on loopback`() {
        val published = compose.services.filterValues { !it.ports.isNullOrEmpty() }.keys

        assertThat(published).containsExactlyInAnyOrder("grafana", "documents-web")
        assertThat(compose.services.values.flatMap { it.ports.orEmpty() }).allMatch { it.startsWith("127.0.0.1:") }
    }

    @Test
    fun `the compose file uses the Compose Specification's own key names`() {
        val yaml = text(Constants.LocalStack.COMPOSE_FILE)

        assertThat(yaml).contains("depends_on:", "condition: \"service_completed_successfully\"", "read_only: true", "start_period:")
        assertThat(yaml).doesNotContain("dependsOn", "readOnly", "startPeriod")
    }

    @Test
    fun `strings stay strings, so compose accepts numbers, booleans and no as values`() {
        // docker compose refuses a bare number in a command ("services.memcached.command.1 must be a
        // string") and a bare number as a user.
        val yaml = text(Constants.LocalStack.COMPOSE_FILE)

        assertThat(
            yaml,
        ).contains(
            "- \"${Constants.LocalStack.MEMCACHED_MEMORY_MB}\"",
            "user: \"0\"",
            "\"GF_USERS_ALLOW_SIGN_UP\": \"false\"",
            "restart: \"no\"",
        )
    }
}
