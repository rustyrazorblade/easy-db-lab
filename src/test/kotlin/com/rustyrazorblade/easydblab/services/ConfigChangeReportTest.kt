package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ConfigHashAnnotator
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * `up` and `grafana update-config` print, for each observability workload, whether the deploy rolls
 * it. Kubernetes rolls a workload on any pod-template change, so the report must say "changed" for a
 * template-only change (a new probe, image or env) as well as for a ConfigMap change — it once
 * reported Pyroscope "unchanged, left running" while a new startup probe rolled it.
 *
 * The running workload's hash is the one the previous deploy rendered and applied; these tests
 * render the Pyroscope server twice, the way two builds would, and compare.
 */
class ConfigChangeReportTest : BaseKoinTest() {
    private lateinit var k8sService: K8sService
    private lateinit var pyroscope: PyroscopeManifestBuilder

    private val controlHost =
        ClusterHost(publicIp = "1.2.3.4", privateIp = "10.0.0.1", alias = "control0", availabilityZone = "us-west-2a")
    private val pyroscopeRef = WorkloadRef(WorkloadKind.Deployment, "pyroscope")

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<K8sService>().also { k8sService = it } }
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "test", versions = mutableMapOf()))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        k8sService = getKoin().get()
        pyroscope = PyroscopeManifestBuilder(getKoin().get())
    }

    private fun hashed(resources: List<HasMetadata>): List<HasMetadata> =
        ConfigHashAnnotator.annotate(resources, mapOf(ClusterConfigData.NAME to mapOf("s3_bucket" to "acct")))

    private fun hashOf(resources: List<HasMetadata>): String =
        resources
            .filterIsInstance<Deployment>()
            .single()
            .spec.template.metadata.annotations
            .getValue(Constants.K8s.CONFIG_HASH_ANNOTATION)

    /** The Pyroscope server as the build before the startup probe rendered it. */
    private fun withoutStartupProbe(resources: List<HasMetadata>): List<HasMetadata> =
        resources.map { resource ->
            if (resource is Deployment) {
                DeploymentBuilder(resource)
                    .editSpec()
                    .editTemplate()
                    .editSpec()
                    .editFirstContainer()
                    .withStartupProbe(null)
                    .endContainer()
                    .endSpec()
                    .endTemplate()
                    .endSpec()
                    .build()
            } else {
                resource
            }
        }

    private fun reportAgainst(
        runningHash: String,
        resources: List<HasMetadata>,
    ): List<Event.Grafana.WorkloadConfigCompared> {
        // An Answer bypasses Kotlin's Result unboxing: the JVM method returns the bare map.
        whenever(k8sService.workloadConfigHashes(any(), any(), any())).thenAnswer { mapOf(pyroscopeRef to runningHash) }
        val emitted = mutableListOf<Event.Grafana.WorkloadConfigCompared>()
        val eventBus = EventBus()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    (envelope.event as? Event.Grafana.WorkloadConfigCompared)?.let { emitted += it }
                }

                override fun close() = Unit
            },
        )
        ConfigChangeReport(k8sService, eventBus).report(controlHost, resources, "default")
        return emitted
    }

    @Test
    fun `a pod template change with the same ConfigMaps is reported as changed`() {
        val running = hashOf(hashed(withoutStartupProbe(pyroscope.buildServerResources())))

        val compared = reportAgainst(running, hashed(pyroscope.buildServerResources()))

        assertThat(compared).containsExactly(Event.Grafana.WorkloadConfigCompared(pyroscopeRef.toString(), changed = true))
    }

    @Test
    fun `an identical render is reported as unchanged`() {
        val running = hashOf(hashed(pyroscope.buildServerResources()))

        val compared = reportAgainst(running, hashed(pyroscope.buildServerResources()))

        assertThat(compared).containsExactly(Event.Grafana.WorkloadConfigCompared(pyroscopeRef.toString(), changed = false))
    }
}
