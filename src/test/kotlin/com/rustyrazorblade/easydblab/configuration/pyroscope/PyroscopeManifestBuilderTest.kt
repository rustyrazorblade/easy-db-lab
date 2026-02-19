package com.rustyrazorblade.easydblab.configuration.pyroscope

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.Deployment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for PyroscopeManifestBuilder.
 *
 * Uses real TemplateService (never mocked per project convention) to verify
 * config file loading from classpath resources.
 */
class PyroscopeManifestBuilderTest : BaseKoinTest() {
    private lateinit var builder: PyroscopeManifestBuilder
    private lateinit var templateService: TemplateService
    private lateinit var mockClusterStateManager: ClusterStateManager

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        mockClusterStateManager = it
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        mockClusterStateManager = getKoin().get()
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = mutableMapOf(),
            ),
        )
        templateService = getKoin().get()
        builder = PyroscopeManifestBuilder(templateService)
    }

    @Test
    fun `buildServerConfigMap loads config yaml from resource`() {
        val configMap = builder.buildServerConfigMap()

        assertThat(configMap.metadata.name).isEqualTo("pyroscope-config")
        assertThat(configMap.metadata.namespace).isEqualTo("default")
        assertThat(configMap.metadata.labels).containsEntry("app.kubernetes.io/name", "pyroscope")
        assertThat(configMap.data).containsKey("config.yaml")
        assertThat(configMap.data["config.yaml"]).contains("http_listen_port: 4040")
        assertThat(configMap.data["config.yaml"]).contains("backend: filesystem")
    }

    @Test
    fun `buildServerService creates ClusterIP service on port 4040`() {
        val service = builder.buildServerService()

        assertThat(service.metadata.name).isEqualTo("pyroscope")
        assertThat(service.metadata.namespace).isEqualTo("default")
        assertThat(service.spec.type).isEqualTo("ClusterIP")
        assertThat(service.spec.ports).hasSize(1)
        assertThat(
            service.spec.ports
                .first()
                .port,
        ).isEqualTo(PyroscopeManifestBuilder.SERVER_PORT)
        assertThat(service.spec.selector).containsEntry("app.kubernetes.io/name", "pyroscope")
    }

    @Test
    fun `buildServerDeployment runs on control plane`() {
        val deployment = builder.buildServerDeployment()
        val podSpec = deployment.spec.template.spec

        assertThat(podSpec.nodeSelector).containsEntry("node-role.kubernetes.io/control-plane", "true")
        assertThat(podSpec.tolerations).anyMatch {
            it.key == "node-role.kubernetes.io/control-plane" && it.operator == "Exists"
        }
    }

    @Test
    fun `buildServerDeployment has hostNetwork enabled`() {
        val deployment = builder.buildServerDeployment()

        assertThat(deployment.spec.template.spec.hostNetwork).isTrue()
        assertThat(deployment.spec.template.spec.dnsPolicy).isEqualTo("ClusterFirstWithHostNet")
    }

    @Test
    fun `buildServerDeployment has correct probes`() {
        val deployment = builder.buildServerDeployment()
        val container =
            deployment.spec.template.spec.containers
                .first()

        assertThat(container.livenessProbe.httpGet.path).isEqualTo("/ready")
        assertThat(container.livenessProbe.httpGet.port.intVal)
            .isEqualTo(PyroscopeManifestBuilder.SERVER_PORT)
        assertThat(container.readinessProbe.httpGet.path).isEqualTo("/ready")
        assertThat(container.readinessProbe.httpGet.port.intVal)
            .isEqualTo(PyroscopeManifestBuilder.SERVER_PORT)
    }

    @Test
    fun `buildServerDeployment has correct volumes`() {
        val deployment = builder.buildServerDeployment()
        val volumes = deployment.spec.template.spec.volumes
        val container =
            deployment.spec.template.spec.containers
                .first()

        // Config volume from ConfigMap
        val configVolume = volumes.find { it.name == "config" }
        assertThat(configVolume).isNotNull
        assertThat(configVolume!!.configMap.name).isEqualTo("pyroscope-config")

        // Data volume from hostPath
        val dataVolume = volumes.find { it.name == "data" }
        assertThat(dataVolume).isNotNull
        assertThat(dataVolume!!.hostPath.path).isEqualTo("/mnt/db1/pyroscope")
        assertThat(dataVolume.hostPath.type).isEqualTo("DirectoryOrCreate")

        // Container volume mounts
        assertThat(container.volumeMounts.find { it.name == "config" }?.mountPath)
            .isEqualTo("/etc/pyroscope")
        assertThat(container.volumeMounts.find { it.name == "data" }?.mountPath)
            .isEqualTo("/data")
    }

    @Test
    fun `buildServerDeployment has no init container`() {
        val deployment = builder.buildServerDeployment()

        assertThat(deployment.spec.template.spec.initContainers).isNullOrEmpty()
    }

    @Test
    fun `buildEbpfConfigMap loads config alloy from resource`() {
        val configMap = builder.buildEbpfConfigMap()

        assertThat(configMap.metadata.name).isEqualTo("pyroscope-ebpf-config")
        assertThat(configMap.metadata.namespace).isEqualTo("default")
        assertThat(configMap.metadata.labels).containsEntry("app.kubernetes.io/name", "pyroscope-ebpf")
        assertThat(configMap.data).containsKey("config.alloy")
        assertThat(configMap.data["config.alloy"]).contains("pyroscope.ebpf")
        assertThat(configMap.data["config.alloy"]).contains("pyroscope.write")
    }

    @Test
    fun `buildEbpfDaemonSet has hostPID and hostNetwork`() {
        val daemonSet = builder.buildEbpfDaemonSet()
        val podSpec = daemonSet.spec.template.spec

        assertThat(podSpec.hostPID).isTrue()
        assertThat(podSpec.hostNetwork).isTrue()
    }

    @Test
    fun `buildEbpfDaemonSet has privileged security context`() {
        val daemonSet = builder.buildEbpfDaemonSet()
        val container =
            daemonSet.spec.template.spec.containers
                .first()

        assertThat(container.securityContext.privileged).isTrue()
        assertThat(container.securityContext.runAsUser).isEqualTo(0L)
        assertThat(container.securityContext.runAsGroup).isEqualTo(0L)
    }

    @Test
    fun `buildEbpfDaemonSet has correct env vars`() {
        val daemonSet = builder.buildEbpfDaemonSet()
        val container =
            daemonSet.spec.template.spec.containers
                .first()
        val envMap = container.env.associateBy { it.name }

        // HOSTNAME from fieldRef
        assertThat(envMap["HOSTNAME"]).isNotNull
        assertThat(envMap["HOSTNAME"]!!.valueFrom.fieldRef.fieldPath).isEqualTo("spec.nodeName")

        // CLUSTER_NAME from configMapKeyRef
        assertThat(envMap["CLUSTER_NAME"]).isNotNull
        assertThat(envMap["CLUSTER_NAME"]!!.valueFrom.configMapKeyRef.name).isEqualTo("cluster-config")
        assertThat(envMap["CLUSTER_NAME"]!!.valueFrom.configMapKeyRef.key).isEqualTo("cluster_name")

        // PYROSCOPE_URL as static value
        assertThat(envMap["PYROSCOPE_URL"]).isNotNull
        assertThat(envMap["PYROSCOPE_URL"]!!.value)
            .isEqualTo("http://pyroscope.default.svc.cluster.local:${PyroscopeManifestBuilder.SERVER_PORT}")
    }

    @Test
    fun `buildEbpfDaemonSet tolerates all nodes`() {
        val daemonSet = builder.buildEbpfDaemonSet()
        val tolerations = daemonSet.spec.template.spec.tolerations

        assertThat(tolerations).anyMatch { it.operator == "Exists" && it.key == null }
    }

    @Test
    fun `buildEbpfDaemonSet has config and sym-cache volumes`() {
        val daemonSet = builder.buildEbpfDaemonSet()
        val volumes = daemonSet.spec.template.spec.volumes
        val container =
            daemonSet.spec.template.spec.containers
                .first()

        assertThat(volumes.find { it.name == "config" }?.configMap?.name)
            .isEqualTo("pyroscope-ebpf-config")
        assertThat(volumes.find { it.name == "sym-cache" }?.emptyDir).isNotNull

        assertThat(container.volumeMounts.find { it.name == "config" }?.mountPath)
            .isEqualTo("/etc/alloy")
        assertThat(container.volumeMounts.find { it.name == "config" }?.readOnly).isTrue()
        assertThat(container.volumeMounts.find { it.name == "sym-cache" }?.mountPath)
            .isEqualTo("/tmp/symb-cache")
    }

    @Test
    fun `buildAllResources returns correct count and types`() {
        val resources = builder.buildAllResources()

        // server ConfigMap + Service + Deployment + eBPF ConfigMap + eBPF DaemonSet = 5
        assertThat(resources).hasSize(5)

        assertThat(resources[0]).isInstanceOf(ConfigMap::class.java)
        assertThat((resources[0] as ConfigMap).metadata.name).isEqualTo("pyroscope-config")

        assertThat(resources[1]).isInstanceOf(Service::class.java)
        assertThat((resources[1] as Service).metadata.name).isEqualTo("pyroscope")

        assertThat(resources[2]).isInstanceOf(Deployment::class.java)
        assertThat((resources[2] as Deployment).metadata.name).isEqualTo("pyroscope")

        assertThat(resources[3]).isInstanceOf(ConfigMap::class.java)
        assertThat((resources[3] as ConfigMap).metadata.name).isEqualTo("pyroscope-ebpf-config")

        assertThat(resources[4]).isInstanceOf(DaemonSet::class.java)
        assertThat((resources[4] as DaemonSet).metadata.name).isEqualTo("pyroscope-ebpf")
    }
}
