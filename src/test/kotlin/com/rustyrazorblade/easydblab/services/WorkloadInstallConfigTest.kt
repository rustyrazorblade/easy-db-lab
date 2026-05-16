package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WorkloadInstallConfigTest {
    private fun parse(yaml: String): WorkloadInstallConfig = installConfigYaml.decodeFromString(WorkloadInstallConfig.serializer(), yaml)

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
            description: Install ClickHouse workload
            collision-check: true
            args: []
            """.trimIndent()
        val config = parse(yaml)
        assertThat(config.name).isEqualTo("clickhouse")
        assertThat(config.description).isEqualTo("Install ClickHouse workload")
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
                suffix: ""
            """.trimIndent()
        val config = parse(yaml)
        assertThat(config.args).hasSize(1)
        val arg = config.args[0]
        assertThat(arg.flag).isEqualTo("--storage-size")
        assertThat(arg.variable).isEqualTo("STORAGE_SIZE")
        assertThat(arg.description).isEqualTo("PVC storage size")
        assertThat(arg.required).isTrue()
        assertThat(arg.type).isEqualTo(WorkloadArgSpec.ArgType.STRING)
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
        assertThat(arg.type).isEqualTo(WorkloadArgSpec.ArgType.STRING)
        assertThat(arg.default).isEmpty()
        assertThat(arg.suffix).isEmpty()
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
            WorkloadArgSpec.ArgType.STRING,
            WorkloadArgSpec.ArgType.BOOLEAN,
            WorkloadArgSpec.ArgType.FLOAT,
            WorkloadArgSpec.ArgType.INT,
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

    @Test
    fun `phase-guards override collision-check`() {
        val config =
            parse(
                """
                name: mydb
                collision-check: false
                phase-guards:
                  start: true
                  stop: false
                """.trimIndent(),
            )
        assertThat(config.isGuardedForPhase("start")).isTrue()
        assertThat(config.isGuardedForPhase("stop")).isFalse()
        assertThat(config.isGuardedForPhase("install")).isFalse()
    }

    @Test
    fun `collision-check true guards start phase by default`() {
        val config =
            parse(
                """
                name: mydb
                collision-check: true
                """.trimIndent(),
            )
        assertThat(config.isGuardedForPhase("start")).isTrue()
        assertThat(config.isGuardedForPhase("install")).isFalse()
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
}
