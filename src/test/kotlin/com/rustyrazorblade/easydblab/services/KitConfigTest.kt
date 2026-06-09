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
                """.trimIndent(),
            )
        assertThat(config.endpoints).hasSize(5)
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
}
