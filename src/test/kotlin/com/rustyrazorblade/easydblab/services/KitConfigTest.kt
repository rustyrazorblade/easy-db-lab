package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class KitConfigTest {
    private fun parse(yaml: String): KitConfig = installConfigYaml.decodeFromString(KitConfig.serializer(), yaml)

    @Test
    fun `parses minimal config with only required fields`() {
        val config = parse("name: mydb")
        assertThat(config.name).isEqualTo("mydb")
        assertThat(config.description).isEmpty()
        assertThat(config.collisionCheck).isFalse()
        assertThat(config.args).isEmpty()
    }

    @Test
    fun `parses full config with all top-level fields`() {
        val yaml =
            """
            name: clickhouse
            description: Install ClickHouse kit
            collision-check: true
            args: []
            """.trimIndent()
        val config = parse(yaml)
        assertThat(config.name).isEqualTo("clickhouse")
        assertThat(config.description).isEqualTo("Install ClickHouse kit")
        assertThat(config.collisionCheck).isTrue()
    }

    @Test
    fun `parses arg with all fields`() {
        val yaml =
            """
            name: mydb
            args:
              - flag: --storage-size
                variable: STORAGE_SIZE
                description: PVC storage size
                required: true
                type: string
                default: 100Gi
            """.trimIndent()
        val config = parse(yaml)
        assertThat(config.args).hasSize(1)
        val arg = config.args[0]
        assertThat(arg.flag).isEqualTo("--storage-size")
        assertThat(arg.variable).isEqualTo("STORAGE_SIZE")
        assertThat(arg.description).isEqualTo("PVC storage size")
        assertThat(arg.required).isTrue()
        assertThat(arg.type).isEqualTo(KitArgSpec.ArgType.STRING)
        assertThat(arg.default).isEqualTo("100Gi")
    }

    @Test
    fun `arg optional fields default correctly`() {
        val yaml =
            """
            name: mydb
            args:
              - flag: --replicas
                variable: REPLICAS
            """.trimIndent()
        val arg = parse(yaml).args[0]
        assertThat(arg.description).isEmpty()
        assertThat(arg.required).isFalse()
        assertThat(arg.type).isEqualTo(KitArgSpec.ArgType.STRING)
        assertThat(arg.default).isEmpty()
    }

    @Test
    fun `parses all arg types`() {
        val yaml =
            """
            name: mydb
            args:
              - flag: --str
                variable: STR
                type: string
              - flag: --flag
                variable: FLAG
                type: boolean
              - flag: --ratio
                variable: RATIO
                type: float
              - flag: --count
                variable: COUNT
                type: int
            """.trimIndent()
        val types = parse(yaml).args.map { it.type }
        assertThat(types).containsExactly(
            KitArgSpec.ArgType.STRING,
            KitArgSpec.ArgType.BOOLEAN,
            KitArgSpec.ArgType.FLOAT,
            KitArgSpec.ArgType.INT,
        )
    }

    @Test
    fun `parses multiple args preserving order`() {
        val yaml =
            """
            name: mydb
            args:
              - flag: --first
                variable: FIRST
              - flag: --second
                variable: SECOND
              - flag: --third
                variable: THIRD
            """.trimIndent()
        val flags = parse(yaml).args.map { it.flag }
        assertThat(flags).containsExactly("--first", "--second", "--third")
    }

    // -------------------------------------------------------------------------
    // Lifecycle phase parsing (task 1.6)
    // -------------------------------------------------------------------------

    @Test
    fun `parses all four lifecycle phases`() {
        val yaml =
            """
            name: mydb
            install:
              - type: helm-repo
                name: stable
                url: https://charts.helm.sh/stable
            start:
              - type: namespace
                name: mydb
            stop:
              - type: delete
                kind: Deployment
                name: mydb
            uninstall:
              - type: helm-uninstall
                release: mydb
                namespace: mydb
            """.trimIndent()
        val config = parse(yaml)
        assertThat(config.install).hasSize(1)
        assertThat(config.start).hasSize(1)
        assertThat(config.stop).hasSize(1)
        assertThat(config.uninstall).hasSize(1)
    }

    @Test
    fun `parses helm-repo step`() {
        val config =
            parse(
                """
                name: mydb
                install:
                  - type: helm-repo
                    name: myrepo
                    url: https://example.com/charts
                """.trimIndent(),
            )
        val step = config.install.single() as InstallStep.HelmRepo
        assertThat(step.name).isEqualTo("myrepo")
        assertThat(step.url).isEqualTo("https://example.com/charts")
    }

    @Test
    fun `parses helm step with all fields`() {
        val config =
            parse(
                """
                name: mydb
                install:
                  - type: helm
                    chart: myrepo/mychart
                    release: myrelease
                    namespace: mynamespace
                    version: 1.2.3
                    values:
                      key1: val1
                      key2: val2
                    values-file: values.yaml
                """.trimIndent(),
            )
        val step = config.install.single() as InstallStep.Helm
        assertThat(step.chart).isEqualTo("myrepo/mychart")
        assertThat(step.release).isEqualTo("myrelease")
        assertThat(step.namespace).isEqualTo("mynamespace")
        assertThat(step.version).isEqualTo("1.2.3")
        assertThat(step.values).containsEntry("key1", "val1").containsEntry("key2", "val2")
        assertThat(step.valuesFile).isEqualTo("values.yaml")
    }

    @Test
    fun `parses helm step with defaults`() {
        val config =
            parse(
                """
                name: mydb
                install:
                  - type: helm
                    chart: myrepo/mychart
                    release: myrelease
                """.trimIndent(),
            )
        val step = config.install.single() as InstallStep.Helm
        assertThat(step.namespace).isEqualTo("default")
        assertThat(step.version).isNull()
        assertThat(step.values).isEmpty()
        assertThat(step.valuesFile).isEmpty()
    }

    @Test
    fun `parses helm-uninstall step`() {
        val config =
            parse(
                """
                name: mydb
                uninstall:
                  - type: helm-uninstall
                    release: myrelease
                    namespace: mynamespace
                """.trimIndent(),
            )
        val step = config.uninstall.single() as InstallStep.HelmUninstall
        assertThat(step.release).isEqualTo("myrelease")
        assertThat(step.namespace).isEqualTo("mynamespace")
    }

    @Test
    fun `parses namespace step`() {
        val config =
            parse(
                """
                name: mydb
                start:
                  - type: namespace
                    name: mynamespace
                """.trimIndent(),
            )
        val step = config.start.single() as InstallStep.Namespace
        assertThat(step.name).isEqualTo("mynamespace")
    }

    @Test
    fun `parses manifest step`() {
        val config =
            parse(
                """
                name: mydb
                start:
                  - type: manifest
                    template: clickhouse.yaml
                """.trimIndent(),
            )
        val step = config.start.single() as InstallStep.Manifest
        assertThat(step.template).isEqualTo("clickhouse.yaml")
    }

    @Test
    fun `parses manifest-url step`() {
        val config =
            parse(
                """
                name: mydb
                install:
                  - type: manifest-url
                    url: https://example.com/operator.yaml
                """.trimIndent(),
            )
        val step = config.install.single() as InstallStep.ManifestUrl
        assertThat(step.url).isEqualTo("https://example.com/operator.yaml")
    }

    @Test
    fun `parses kustomize step`() {
        val config =
            parse(
                """
                name: mydb
                install:
                  - type: kustomize
                    url: https://github.com/example/repo//config
                """.trimIndent(),
            )
        val step = config.install.single() as InstallStep.Kustomize
        assertThat(step.url).isEqualTo("https://github.com/example/repo//config")
    }

    @Test
    fun `parses wait step with all fields`() {
        val config =
            parse(
                """
                name: mydb
                start:
                  - type: wait
                    kind: Deployment
                    name: myapp
                    namespace: mynamespace
                    condition: Available
                    timeout: 600s
                """.trimIndent(),
            )
        val step = config.start.single() as InstallStep.Wait
        assertThat(step.kind).isEqualTo("Deployment")
        assertThat(step.name).isEqualTo("myapp")
        assertThat(step.namespace).isEqualTo("mynamespace")
        assertThat(step.condition).isEqualTo("Available")
        assertThat(step.timeout).isEqualTo("600s")
    }

    @Test
    fun `parses wait step with defaults`() {
        val config =
            parse(
                """
                name: mydb
                start:
                  - type: wait
                    kind: Deployment
                    name: myapp
                """.trimIndent(),
            )
        val step = config.start.single() as InstallStep.Wait
        assertThat(step.namespace).isNull()
        assertThat(step.condition).isEqualTo("Available")
        assertThat(step.timeout).isEqualTo("300s")
    }

    @Test
    fun `parses delete step`() {
        val config =
            parse(
                """
                name: mydb
                stop:
                  - type: delete
                    kind: Deployment
                    name: myapp
                    namespace: mynamespace
                    ignore-not-found: false
                """.trimIndent(),
            )
        val step = config.stop.single() as InstallStep.Delete
        assertThat(step.kind).isEqualTo("Deployment")
        assertThat(step.name).isEqualTo("myapp")
        assertThat(step.namespace).isEqualTo("mynamespace")
        assertThat(step.ignoreNotFound).isFalse()
    }

    @Test
    fun `parses platform-pvs step`() {
        val config =
            parse(
                """
                name: mydb
                install:
                  - type: platform-pvs
                    count: 3
                    node-type: app
                """.trimIndent(),
            )
        val step = config.install.single() as InstallStep.PlatformPvs
        assertThat(step.count).isEqualTo(3)
        assertThat(step.nodeType).isEqualTo("app")
    }

    @Test
    fun `parses configmap step`() {
        val config =
            parse(
                """
                name: mydb
                start:
                  - type: configmap
                    name: myconfig
                    namespace: mynamespace
                    data:
                      key1: value1
                      key2: value2
                """.trimIndent(),
            )
        val step = config.start.single() as InstallStep.ConfigMap
        assertThat(step.name).isEqualTo("myconfig")
        assertThat(step.namespace).isEqualTo("mynamespace")
        assertThat(step.data).containsEntry("key1", "value1").containsEntry("key2", "value2")
    }

    @Test
    fun `parses label step`() {
        val config =
            parse(
                """
                name: mydb
                start:
                  - type: label
                    node-type: db
                    labels:
                      role: database
                      tier: data
                """.trimIndent(),
            )
        val step = config.start.single() as InstallStep.Label
        assertThat(step.nodeType).isEqualTo("db")
        assertThat(step.labels).containsEntry("role", "database").containsEntry("tier", "data")
    }

    @Test
    fun `parses exec step`() {
        val config =
            parse(
                """
                name: mydb
                start:
                  - type: exec
                    pod: mypod
                    namespace: mynamespace
                    command:
                      - clickhouse-client
                      - --query
                      - SELECT 1
                """.trimIndent(),
            )
        val step = config.start.single() as InstallStep.Exec
        assertThat(step.pod).isEqualTo("mypod")
        assertThat(step.namespace).isEqualTo("mynamespace")
        assertThat(step.command).containsExactly("clickhouse-client", "--query", "SELECT 1")
    }

    @Test
    fun `parses shell step`() {
        val config =
            parse(
                """
                name: mydb
                start:
                  - type: shell
                    script: echo hello
                """.trimIndent(),
            )
        val step = config.start.single() as InstallStep.Shell
        assertThat(step.script).isEqualTo("echo hello")
    }

    @Test
    fun `unknown step type throws`() {
        assertThatThrownBy {
            parse(
                """
                name: mydb
                install:
                  - type: does-not-exist
                    name: foo
                """.trimIndent(),
            )
        }.isInstanceOf(Exception::class.java)
    }

    // -------------------------------------------------------------------------
    // Metrics block deserialization (task 2.5)
    // -------------------------------------------------------------------------

    @Test
    fun `parses single scrape target as list with explicit path`() {
        val config =
            parse(
                """
                name: clickhouse
                metrics:
                  - type: scrape
                    port: 9363
                    path: /metrics
                """.trimIndent(),
            )
        assertThat(config.metrics).hasSize(1)
        val metrics = config.metrics[0] as KitMetrics.Scrape
        assertThat(metrics.port).isEqualTo(9363)
        assertThat(metrics.path).isEqualTo("/metrics")
    }

    @Test
    fun `parses scrape metrics with default path`() {
        val config =
            parse(
                """
                name: mydb
                metrics:
                  - type: scrape
                    port: 9100
                """.trimIndent(),
            )
        assertThat(config.metrics).hasSize(1)
        val metrics = config.metrics[0] as KitMetrics.Scrape
        assertThat(metrics.port).isEqualTo(9100)
        assertThat(metrics.path).isEqualTo("/metrics")
    }

    @Test
    fun `parses scrape metrics with job field`() {
        val config =
            parse(
                """
                name: tidb
                metrics:
                  - type: scrape
                    port: 31080
                    path: /metrics
                    job: tidb-sql
                  - type: scrape
                    port: 32180
                    path: /metrics
                    job: tikv
                """.trimIndent(),
            )
        assertThat(config.metrics).hasSize(2)
        val first = config.metrics[0] as KitMetrics.Scrape
        assertThat(first.port).isEqualTo(31080)
        assertThat(first.job).isEqualTo("tidb-sql")
        val second = config.metrics[1] as KitMetrics.Scrape
        assertThat(second.port).isEqualTo(32180)
        assertThat(second.job).isEqualTo("tikv")
    }

    @Test
    fun `scrape job field defaults to empty string`() {
        val config =
            parse(
                """
                name: clickhouse
                metrics:
                  - type: scrape
                    port: 9363
                """.trimIndent(),
            )
        val metrics = config.metrics[0] as KitMetrics.Scrape
        assertThat(metrics.job).isEmpty()
    }

    @Test
    fun `parses java-agent metrics`() {
        val config =
            parse(
                """
                name: presto
                metrics:
                  - type: java-agent
                    service-name: presto
                """.trimIndent(),
            )
        assertThat(config.metrics).hasSize(1)
        val metrics = config.metrics[0] as KitMetrics.JavaAgent
        assertThat(metrics.serviceName).isEqualTo("presto")
    }

    @Test
    fun `parses helm-native metrics`() {
        val config =
            parse(
                """
                name: mydb
                metrics:
                  - type: helm-native
                """.trimIndent(),
            )
        assertThat(config.metrics).hasSize(1)
        assertThat(config.metrics[0]).isEqualTo(KitMetrics.HelmNative)
    }

    @Test
    fun `metrics defaults to empty list when not declared`() {
        val config = parse("name: mydb")
        assertThat(config.metrics).isEmpty()
    }

    @Test
    fun `parses backup and restore phases`() {
        val yaml =
            """
            name: clickhouse
            backup:
              - type: shell
                script: |
                  kubectl exec mypod -- clickhouse-client --query "BACKUP DATABASE default TO Disk('s3_backup', '${"$"}{BACKUP_NAME}/')"
            restore:
              - type: shell
                script: |
                  kubectl exec mypod -- clickhouse-client --query "RESTORE DATABASE default FROM Disk('s3_backup', '${"$"}{BACKUP_NAME}/')"
            """.trimIndent()
        val config = parse(yaml)
        assertThat(config.backup).hasSize(1)
        assertThat(config.restore).hasSize(1)
        assertThat(config.backup[0]).isInstanceOf(InstallStep.Shell::class.java)
        assertThat(config.restore[0]).isInstanceOf(InstallStep.Shell::class.java)
    }

    @Test
    fun `backup and restore default to empty lists`() {
        val config = parse("name: mydb")
        assertThat(config.backup).isEmpty()
        assertThat(config.restore).isEmpty()
    }

    @Test
    fun `dashboards list parses correctly`() {
        val config =
            parse(
                """
                name: mydb
                dashboards:
                  - path: dashboards/overview.json
                    name: Overview
                  - path: dashboards/detail.json
                """.trimIndent(),
            )
        assertThat(config.dashboards).hasSize(2)
        assertThat(config.dashboards[0].path).isEqualTo("dashboards/overview.json")
        assertThat(config.dashboards[0].name).isEqualTo("Overview")
        assertThat(config.dashboards[1].path).isEqualTo("dashboards/detail.json")
        assertThat(config.dashboards[1].name).isEmpty()
    }

    // -------------------------------------------------------------------------
    // endpoints and runtime deserialization
    // -------------------------------------------------------------------------

    @Test
    fun `endpoints default to empty list when not declared`() {
        val config = parse("name: mydb")
        assertThat(config.endpoints).isEmpty()
    }

    @Test
    fun `runtime defaults to null when not declared`() {
        val config = parse("name: mydb")
        assertThat(config.runtime).isNull()
    }

    @Test
    fun `parses endpoints with all endpoint types`() {
        val config =
            parse(
                """
                name: presto
                endpoints:
                  - name: "Presto UI"
                    node-type: app
                    port: 8080
                    type: http
                  - name: "JDBC"
                    node-type: app
                    port: 8080
                    type: jdbc
                    scheme: presto
                    path: /cassandra
                  - name: "Native"
                    node-type: db
                    port: 9000
                    type: native
                  - name: "CQL"
                    node-type: db
                    port: 9042
                    type: cql
                  - name: "HTTPS"
                    node-type: control
                    port: 443
                    type: https
                  - name: "Kafka"
                    node-type: db
                    port: 9092
                    type: kafka
                """.trimIndent(),
            )
        assertThat(config.endpoints).hasSize(6)
        assertThat(config.endpoints[0].name).isEqualTo("Presto UI")
        assertThat(config.endpoints[0].nodeType).isEqualTo("app")
        assertThat(config.endpoints[0].port).isEqualTo(8080)
        assertThat(config.endpoints[0].type).isEqualTo(KitEndpoint.EndpointType.HTTP)
        assertThat(config.endpoints[1].type).isEqualTo(KitEndpoint.EndpointType.JDBC)
        assertThat(config.endpoints[1].scheme).isEqualTo("presto")
        assertThat(config.endpoints[1].path).isEqualTo("/cassandra")
        assertThat(config.endpoints[2].type).isEqualTo(KitEndpoint.EndpointType.NATIVE)
        assertThat(config.endpoints[3].type).isEqualTo(KitEndpoint.EndpointType.CQL)
        assertThat(config.endpoints[4].type).isEqualTo(KitEndpoint.EndpointType.HTTPS)
        assertThat(config.endpoints[5].type).isEqualTo(KitEndpoint.EndpointType.KAFKA)
    }

    @Test
    fun `kafka endpoint formatUrl returns host colon port with no scheme`() {
        listOf(
            Triple("Kafka Internal", 9092, "10.0.0.5"),
            Triple("Kafka External", 30092, "100.64.1.2"),
        ).forEach { (name, port, ip) ->
            val endpoint =
                KitEndpoint(
                    name = name,
                    nodeType = "db",
                    port = port,
                    type = KitEndpoint.EndpointType.KAFKA,
                )
            assertThat(endpoint.formatUrl(ip)).isEqualTo("$ip:$port")
        }
    }

    @Test
    fun `formatUrl port override replaces the port for every url-bearing endpoint type`() {
        data class Case(
            val type: KitEndpoint.EndpointType,
            val expectedDefault: String,
            val expectedOverride: String,
        )

        val ip = "10.0.1.10"
        val declaredPort = 8080
        val overridePort = 54321
        val cases =
            listOf(
                Case(KitEndpoint.EndpointType.HTTP, "http://$ip:$declaredPort/p", "http://$ip:$overridePort/p"),
                Case(KitEndpoint.EndpointType.HTTPS, "https://$ip:$declaredPort/p", "https://$ip:$overridePort/p"),
                Case(KitEndpoint.EndpointType.JDBC, "jdbc:presto://$ip:$declaredPort/p", "jdbc:presto://$ip:$overridePort/p"),
                Case(KitEndpoint.EndpointType.NATIVE, "$ip:$declaredPort", "$ip:$overridePort"),
                Case(KitEndpoint.EndpointType.CQL, "$ip:$declaredPort", "$ip:$overridePort"),
                Case(KitEndpoint.EndpointType.KAFKA, "$ip:$declaredPort", "$ip:$overridePort"),
                Case(KitEndpoint.EndpointType.POSTGRESQL, "postgresql://$ip:$declaredPort/db", "postgresql://$ip:$overridePort/db"),
                Case(KitEndpoint.EndpointType.MYSQL, "mysql://$ip:$declaredPort/db", "mysql://$ip:$overridePort/db"),
            )

        cases.forEach { case ->
            val endpoint =
                KitEndpoint(
                    name = "test",
                    nodeType = "db",
                    port = declaredPort,
                    type = case.type,
                    scheme = "presto",
                    path = "/p",
                    database = "db",
                )
            // Single-arg overload uses the endpoint's declared port.
            assertThat(endpoint.formatUrl(ip)).isEqualTo(case.expectedDefault)
            // Port override substitutes only the port.
            assertThat(endpoint.formatUrl(ip, overridePort)).isEqualTo(case.expectedOverride)
        }
    }

    @Test
    fun `endpoint scheme and path default to empty strings`() {
        val config =
            parse(
                """
                name: mydb
                endpoints:
                  - name: "UI"
                    node-type: app
                    port: 8080
                    type: http
                """.trimIndent(),
            )
        val endpoint = config.endpoints.single()
        assertThat(endpoint.scheme).isEmpty()
        assertThat(endpoint.path).isEmpty()
    }

    @Test
    fun `parses helm runtime block`() {
        val config =
            parse(
                """
                name: presto
                runtime:
                  type: helm
                  release: presto
                  namespace: default
                """.trimIndent(),
            )
        val runtime = requireNotNull(config.runtime) { "runtime should be present" }
        assertThat(runtime.type).isEqualTo(KitRuntime.RuntimeType.HELM)
        assertThat(runtime.release).isEqualTo("presto")
        assertThat(runtime.namespace).isEqualTo("default")
    }

    @Test
    fun `parses pods runtime block with selector`() {
        val config =
            parse(
                """
                name: clickhouse
                runtime:
                  type: pods
                  selector: "clickhouse.altinity.com/chi=clickhouse"
                  namespace: default
                """.trimIndent(),
            )
        val runtime = requireNotNull(config.runtime) { "runtime should be present" }
        assertThat(runtime.type).isEqualTo(KitRuntime.RuntimeType.PODS)
        assertThat(runtime.selector).isEqualTo("clickhouse.altinity.com/chi=clickhouse")
        assertThat(runtime.namespace).isEqualTo("default")
    }

    @Test
    fun `parses deployment and statefulset runtime types`() {
        val deployConfig =
            parse(
                """
                name: mydb
                runtime:
                  type: deployment
                  name: mydb
                  namespace: myns
                """.trimIndent(),
            )
        assertThat(requireNotNull(deployConfig.runtime).type).isEqualTo(KitRuntime.RuntimeType.DEPLOYMENT)

        val ssConfig =
            parse(
                """
                name: mydb
                runtime:
                  type: statefulset
                  name: mydb
                  namespace: myns
                """.trimIndent(),
            )
        assertThat(requireNotNull(ssConfig.runtime).type).isEqualTo(KitRuntime.RuntimeType.STATEFULSET)
    }

    @Test
    fun `runtime namespace defaults to default`() {
        val config =
            parse(
                """
                name: presto
                runtime:
                  type: helm
                  release: presto
                """.trimIndent(),
            )
        assertThat(requireNotNull(config.runtime).namespace).isEqualTo("default")
    }

    // -------------------------------------------------------------------------
    // hooks deserialization
    // -------------------------------------------------------------------------

    @Test
    fun `hooks absent when not declared`() {
        val config = parse("name: mydb")
        assertThat(config.hooks).isNull()
    }

    @Test
    fun `parses post-workload-start hook with script`() {
        val config =
            parse(
                """
                name: presto
                hooks:
                  post-workload-start:
                    script: bin/update-catalogs.sh
                """.trimIndent(),
            )
        val hook = requireNotNull(config.hooks?.postWorkloadStart)
        assertThat(hook.script).isEqualTo("bin/update-catalogs.sh")
        assertThat(hook.workloads).isEmpty()
    }

    @Test
    fun `parses post-workload-stop hook with script`() {
        val config =
            parse(
                """
                name: presto
                hooks:
                  post-workload-stop:
                    script: bin/update-catalogs.sh
                """.trimIndent(),
            )
        val hook = requireNotNull(config.hooks?.postWorkloadStop)
        assertThat(hook.script).isEqualTo("bin/update-catalogs.sh")
        assertThat(hook.workloads).isEmpty()
    }

    @Test
    fun `parses both hooks in a single config`() {
        val config =
            parse(
                """
                name: presto
                hooks:
                  post-workload-start:
                    script: bin/update-catalogs.sh
                  post-workload-stop:
                    script: bin/update-catalogs.sh
                """.trimIndent(),
            )
        assertThat(config.hooks?.postWorkloadStart).isNotNull()
        assertThat(config.hooks?.postWorkloadStop).isNotNull()
    }

    @Test
    fun `parses hook with workload scoping list`() {
        val config =
            parse(
                """
                name: presto
                hooks:
                  post-workload-start:
                    script: bin/update-catalogs.sh
                    workloads:
                      - cassandra
                      - clickhouse
                """.trimIndent(),
            )
        val hook = requireNotNull(config.hooks?.postWorkloadStart)
        assertThat(hook.workloads).containsExactly("cassandra", "clickhouse")
    }

    @Test
    fun `stepsForPhase returns empty list for unknown phase name`() {
        val config = parse("name: mydb")
        // Previously threw; now returns empty so script-only phases (e.g. update-catalogs)
        // can fall through to findScriptFile() in KitRunnerCommand.
        assertThat(config.stepsForPhase("update-catalogs")).isEmpty()
    }

    @Test
    fun `stepsForPhase returns empty list for known phase with no steps`() {
        val config = parse("name: mydb")
        assertThat(config.stepsForPhase("start")).isEmpty()
    }

    // -------------------------------------------------------------------------
    // capabilities deserialization
    // -------------------------------------------------------------------------

    @Test
    fun `capabilities defaults to empty list when not declared`() {
        val config = parse("name: mydb")
        assertThat(config.capabilities).isEmpty()
    }

    @Test
    fun `parses sql capability with all fields`() {
        val config =
            parse(
                """
                name: mydb
                capabilities:
                  - type: sql
                    user: easy-db-lab
                    driver-class: com.facebook.presto.jdbc.PrestoDriver
                """.trimIndent(),
            )
        assertThat(config.capabilities).hasSize(1)
        val cap = config.capabilities[0]
        assertThat(cap.type).isEqualTo("sql")
        assertThat(cap.user).isEqualTo("easy-db-lab")
        assertThat(cap.driverClass).isEqualTo("com.facebook.presto.jdbc.PrestoDriver")
    }

    @Test
    fun `parses sql capability with defaults`() {
        val config =
            parse(
                """
                name: mydb
                capabilities:
                  - type: sql
                """.trimIndent(),
            )
        val cap = config.capabilities[0]
        assertThat(cap.user).isEmpty()
        assertThat(cap.driverClass).isEmpty()
    }

    @Test
    fun `unknown capability type parses without error`() {
        val config =
            parse(
                """
                name: mydb
                capabilities:
                  - type: tpch-load
                    user: test
                """.trimIndent(),
            )
        assertThat(config.capabilities).hasSize(1)
        assertThat(config.capabilities[0].type).isEqualTo("tpch-load")
    }

    // -------------------------------------------------------------------------
    // wire-protocol endpoint types (postgresql, mysql) and database field
    // -------------------------------------------------------------------------

    @Test
    fun `parses postgresql endpoint with port and database`() {
        val config =
            parse(
                """
                name: mydb
                endpoints:
                  - name: "PostgreSQL wire"
                    node-type: db
                    port: 5432
                    type: postgresql
                    database: mydb
                """.trimIndent(),
            )
        val endpoint = config.endpoints.single()
        assertThat(endpoint.type).isEqualTo(KitEndpoint.EndpointType.POSTGRESQL)
        assertThat(endpoint.port).isEqualTo(5432)
        assertThat(endpoint.database).isEqualTo("mydb")
    }

    @Test
    fun `parses mysql endpoint with port and database`() {
        val config =
            parse(
                """
                name: mydb
                endpoints:
                  - name: "MySQL wire"
                    node-type: db
                    port: 3306
                    type: mysql
                    database: bench
                """.trimIndent(),
            )
        val endpoint = config.endpoints.single()
        assertThat(endpoint.type).isEqualTo(KitEndpoint.EndpointType.MYSQL)
        assertThat(endpoint.port).isEqualTo(3306)
        assertThat(endpoint.database).isEqualTo("bench")
    }

    @Test
    fun `database field defaults to empty string when absent`() {
        val config =
            parse(
                """
                name: mydb
                endpoints:
                  - name: "JDBC"
                    node-type: db
                    port: 30123
                    type: jdbc
                    scheme: clickhouse
                """.trimIndent(),
            )
        assertThat(config.endpoints.single().database).isEmpty()
    }

    @Test
    fun `existing endpoint types unaffected by new types`() {
        val config =
            parse(
                """
                name: mydb
                endpoints:
                  - name: "HTTP"
                    node-type: app
                    port: 8080
                    type: http
                  - name: "JDBC"
                    node-type: db
                    port: 30123
                    type: jdbc
                    scheme: clickhouse
                  - name: "Native"
                    node-type: db
                    port: 9000
                    type: native
                  - name: "CQL"
                    node-type: db
                    port: 9042
                    type: cql
                """.trimIndent(),
            )
        assertThat(config.endpoints.map { it.type }).containsExactly(
            KitEndpoint.EndpointType.HTTP,
            KitEndpoint.EndpointType.JDBC,
            KitEndpoint.EndpointType.NATIVE,
            KitEndpoint.EndpointType.CQL,
        )
    }

    // -------------------------------------------------------------------------
    // kit-ref arg type and capability field
    // -------------------------------------------------------------------------

    @Test
    fun `parses kit-ref arg type`() {
        val config =
            parse(
                """
                name: sysbench
                args:
                  - flag: --target
                    variable: TARGET
                    type: kit-ref
                    capability: sql
                    description: Kit to benchmark
                    required: true
                """.trimIndent(),
            )
        val arg = config.args.single()
        assertThat(arg.type).isEqualTo(KitArgSpec.ArgType.KIT_REF)
        assertThat(arg.capability).isEqualTo("sql")
        assertThat(arg.variable).isEqualTo("TARGET")
    }

    @Test
    fun `capability field defaults to empty string when absent`() {
        val config =
            parse(
                """
                name: mydb
                args:
                  - flag: --workers
                    variable: WORKERS
                    type: int
                """.trimIndent(),
            )
        assertThat(config.args.single().capability).isEmpty()
    }

    // -------------------------------------------------------------------------
    // commands map deserialization (kit-command-args)
    // -------------------------------------------------------------------------

    @Test
    fun `commands defaults to empty map when not declared`() {
        val config = parse("name: mydb")
        assertThat(config.commands).isEmpty()
    }

    @Test
    fun `parses commands block with description and args`() {
        val config =
            parse(
                """
                name: kafka
                commands:
                  producer-perf:
                    description: "Run producer perf test"
                    args:
                      - flag: --num-records
                        variable: NUM_RECORDS
                        type: int
                        default: "1000000"
                      - flag: --throughput
                        variable: THROUGHPUT
                        type: int
                        default: "-1"
                """.trimIndent(),
            )
        assertThat(config.commands).hasSize(1)
        val cmd = config.commands["producer-perf"]!!
        assertThat(cmd.description).isEqualTo("Run producer perf test")
        assertThat(cmd.args).hasSize(2)
        assertThat(cmd.args[0].flag).isEqualTo("--num-records")
        assertThat(cmd.args[0].variable).isEqualTo("NUM_RECORDS")
        assertThat(cmd.args[0].type).isEqualTo(KitArgSpec.ArgType.INT)
        assertThat(cmd.args[0].default).isEqualTo("1000000")
        assertThat(cmd.args[1].flag).isEqualTo("--throughput")
    }

    @Test
    fun `parses multiple commands each with independent args`() {
        val config =
            parse(
                """
                name: kafka
                commands:
                  producer-perf:
                    args:
                      - flag: --num-records
                        variable: NUM_RECORDS
                        type: int
                        default: "1000000"
                  consumer-perf:
                    args:
                      - flag: --group
                        variable: GROUP
                        type: string
                        default: "bench"
                """.trimIndent(),
            )
        assertThat(config.commands).hasSize(2)
        assertThat(config.commands["producer-perf"]!!.args[0].flag).isEqualTo("--num-records")
        assertThat(config.commands["consumer-perf"]!!.args[0].flag).isEqualTo("--group")
    }

    @Test
    fun `command spec description defaults to empty string`() {
        val config =
            parse(
                """
                name: mydb
                commands:
                  start:
                    args:
                      - flag: --threads
                        variable: THREADS
                        type: int
                        default: "4"
                """.trimIndent(),
            )
        assertThat(config.commands["start"]!!.description).isEmpty()
    }

    @Test
    fun `command spec args default to empty list`() {
        val config =
            parse(
                """
                name: mydb
                commands:
                  start:
                    description: "Start the workload"
                """.trimIndent(),
            )
        assertThat(config.commands["start"]!!.args).isEmpty()
    }

    @Test
    fun `kit-ref arg parses alongside other arg types`() {
        val config =
            parse(
                """
                name: sysbench
                args:
                  - flag: --target
                    variable: TARGET
                    type: kit-ref
                  - flag: --threads
                    variable: THREADS
                    type: int
                  - flag: --workload
                    variable: WORKLOAD
                    type: string
                """.trimIndent(),
            )
        assertThat(config.args.map { it.type }).containsExactly(
            KitArgSpec.ArgType.KIT_REF,
            KitArgSpec.ArgType.INT,
            KitArgSpec.ArgType.STRING,
        )
    }
}

/**
 * Tests for KitEndpoint.toTargetVars — ensures each endpoint type produces the correct TARGET_* vars.
 */
class KitEndpointToTargetVarsTest {
    private val sqlCap = KitCapability(type = "sql", user = "bench", driverClass = "com.example.Driver")

    private fun endpoint(
        type: KitEndpoint.EndpointType,
        port: Int = 5432,
        scheme: String = "",
        path: String = "",
        database: String = "testdb",
    ) = KitEndpoint(name = "test", nodeType = "db", port = port, type = type, scheme = scheme, path = path, database = database)

    @Test
    fun `jdbc endpoint produces TARGET_JDBC vars`() {
        val vars =
            endpoint(
                KitEndpoint.EndpointType.JDBC,
                port = 8123,
                scheme = "clickhouse",
                path = "/default",
            ).toTargetVars("10.0.0.1", sqlCap)
        assertThat(vars["TARGET_JDBC_URL"]).isEqualTo("jdbc:clickhouse://10.0.0.1:8123/default")
        assertThat(vars["TARGET_JDBC_USER"]).isEqualTo("bench")
        assertThat(vars["TARGET_JDBC_DRIVER"]).isEqualTo("com.example.Driver")
    }

    @Test
    fun `postgresql endpoint produces TARGET_PG vars`() {
        val vars = endpoint(KitEndpoint.EndpointType.POSTGRESQL, port = 5432, database = "benchdb").toTargetVars("10.0.0.2", sqlCap)
        assertThat(vars["TARGET_PG_HOST"]).isEqualTo("10.0.0.2")
        assertThat(vars["TARGET_PG_PORT"]).isEqualTo("5432")
        assertThat(vars["TARGET_PG_USER"]).isEqualTo("bench")
        assertThat(vars["TARGET_PG_DATABASE"]).isEqualTo("benchdb")
    }

    @Test
    fun `mysql endpoint produces TARGET_MYSQL vars`() {
        val vars = endpoint(KitEndpoint.EndpointType.MYSQL, port = 3306, database = "sbtest").toTargetVars("10.0.0.3", sqlCap)
        assertThat(vars["TARGET_MYSQL_HOST"]).isEqualTo("10.0.0.3")
        assertThat(vars["TARGET_MYSQL_PORT"]).isEqualTo("3306")
        assertThat(vars["TARGET_MYSQL_USER"]).isEqualTo("bench")
        assertThat(vars["TARGET_MYSQL_DATABASE"]).isEqualTo("sbtest")
    }

    @Test
    fun `http endpoint produces TARGET_HTTP_URL`() {
        val vars = endpoint(KitEndpoint.EndpointType.HTTP, port = 8080, database = "").toTargetVars("10.0.0.4", null)
        assertThat(vars["TARGET_HTTP_URL"]).isEqualTo("http://10.0.0.4:8080")
    }

    @Test
    fun `native and cql endpoints produce no vars`() {
        assertThat(endpoint(KitEndpoint.EndpointType.NATIVE).toTargetVars("10.0.0.5", sqlCap)).isEmpty()
        assertThat(endpoint(KitEndpoint.EndpointType.CQL).toTargetVars("10.0.0.5", sqlCap)).isEmpty()
    }

    @Test
    fun `null sql capability produces empty user and driver`() {
        val vars = endpoint(KitEndpoint.EndpointType.JDBC, scheme = "postgresql").toTargetVars("10.0.0.6", null)
        assertThat(vars["TARGET_JDBC_USER"]).isEmpty()
        assertThat(vars["TARGET_JDBC_DRIVER"]).isEmpty()
    }
}
