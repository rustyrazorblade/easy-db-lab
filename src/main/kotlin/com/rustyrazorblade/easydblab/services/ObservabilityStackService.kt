package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.BackendStorage
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.ConfigHashAnnotator
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata

/**
 * Builds and applies the full observability stack to the K8s cluster.
 *
 * This is the orchestration extracted out of the `grafana update-config` command so that both the
 * command and cluster bring-up (`up`) drive one code path. It owns the whole sequence: the runtime
 * `cluster-config` ConfigMap, every Fabric8-built resource, the Pyroscope, Tempo, Mimir, Loki and
 * Grafana on-node data directories, dashboard upload, the wait for the applied workloads' rollouts to complete, and
 * the final namespace-wide readiness gate.
 *
 * No workload is force-restarted. Each workload's pod template carries a hash of the ConfigMaps it
 * reads ([ConfigHashAnnotator]), so Kubernetes rolls exactly the workloads whose configuration
 * changed and leaves the rest running — a dashboard edit no longer restarts Tempo or Pyroscope.
 * [ConfigChangeReport] tells the operator which workloads that is.
 *
 * Redirect mode is a first-class branch, not a set of skipped steps bolted on: when the cluster's
 * init config records a [TelemetryRedirect], the four local backends (Mimir, Loki, Tempo,
 * the Pyroscope server) plus Grafana and its dashboards are never built, and the collectors and the
 * Pyroscope eBPF agent are pointed at the external stack instead. The rollout-wait list is derived
 * from the resources actually applied, so redirect never waits on a workload it did not deploy.
 */
interface ObservabilityStackService {
    /**
     * Deploys the observability stack to the cluster in the workspace kubeconfig.
     *
     * The mode comes from the cluster state: when its init config records a [TelemetryRedirect],
     * the collectors are pointed at that external stack and the local backends, Grafana, and
     * dashboards are skipped. Reading it from the state, not from the caller, is what keeps the
     * collector this deploy applies identical to the one a kit sync applies ([CollectorResources]).
     *
     * The control node in the cluster state is needed only where the stack uses the node itself: the
     * host-path state directories and dashboard tree, and the node IP the backends publish on under
     * [com.rustyrazorblade.easydblab.configuration.Placement.CONTROL_NODE]. Off owned nodes the deploy
     * needs no control node.
     *
     * @return success once the stack is applied and the namespace reaches Ready; failure otherwise.
     */
    fun deploy(): Result<Unit>
}

/**
 * Default implementation that applies the stack over the K8s API and configures on-node
 * directories over SSH. Its collaborators are grouped by concern: what the deploy reads
 * ([ObservabilityDeployInputs]), what it renders ([ObservabilityStageManifests]), and Grafana
 * ([GrafanaStage]).
 */
class DefaultObservabilityStackService(
    private val k8sService: K8sService,
    remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
    private val inputs: ObservabilityDeployInputs,
    private val manifests: ObservabilityStageManifests,
    private val grafana: GrafanaStage,
    private val configChangeReport: ConfigChangeReport,
) : ObservabilityStackService {
    companion object {
        private const val DEFAULT_NAMESPACE = "default"
    }

    /**
     * One deploy stage: a human label and the resources it applies. A stage is applied only where
     * the environment provides every capability in [requires]; a stage that needs more than one
     * (e.g. journald needs a systemd journal *and* a privileged pod to read it) is skipped if any is
     * absent. Every workload in [rolledOutFirst] has finished rolling out before the stage is applied.
     */
    private data class Stage(
        val label: String,
        val resources: List<HasMetadata>,
        val requires: Set<NodeCapability> = emptySet(),
        val rolledOutFirst: List<WorkloadRef> = emptyList(),
    )

    /**
     * The stages the environment can run. Not a pure filter: a stage it cannot run is reported as
     * skipped with one [Event.Grafana.StageSkipped] per capability it is missing, so the operator
     * sees every reason the stage was left out. Call it once, before anything is applied.
     */
    private fun applyCapabilityGate(
        stages: List<Stage>,
        capabilities: NodeCapabilities,
    ): List<Stage> =
        stages.filter { stage ->
            val missing = stage.requires.filter { it !in capabilities }
            missing.forEach { eventBus.emit(Event.Grafana.StageSkipped(stage.label, it)) }
            missing.isEmpty()
        }

    private val stateDirectories = OnNodeStateDirectories(remoteOps, eventBus)
    private val retainedClaimCheck = RetainedClaimCheck(k8sService)
    private val placementCheck = PlacementCheck(k8sService)

    override fun deploy(): Result<Unit> =
        runCatching {
            val reading = inputs.read()
            val clusterState = reading.state
            val telemetryRedirect = clusterState.initConfig?.telemetryRedirect
            val contract = reading.contract
            val hosting = contract.hosting
            // Before anything is applied: a claim on a class that deletes its volume would lose the state.
            retainedClaimCheck.require(hosting.storage)
            // A selector no node matches would leave every backend Pending until the rollout wait times out.
            placementCheck.require(hosting.placement)

            val clusterConfig = createClusterConfigMap(reading)
            val collector = Stage("OTel Collector", manifests.coreCollector.resources(reading))

            // The local backends and Grafana are local-mode only. The Pyroscope server directory and
            // dashboard upload hang off those, so they are guarded the same way.
            reportPlacement(hosting)
            val stages =
                applyCapabilityGate(
                    buildStages(telemetryRedirect, clusterState.initConfig?.cni ?: CniMode.Flannel, hosting),
                    contract.nodeCapabilities,
                )

            // The Pyroscope server, Tempo, Mimir and Loki write to host directories; the agents do
            // not, so prepare them only in local mode, and only where state lives on the node.
            val onNodeState = telemetryRedirect == null && hosting.storage == BackendStorage.HostPath
            if (onNodeState) {
                stateDirectories.prepareBackends(stateNode(clusterState))
            }

            val appliedResources = applyHashed(collector, stages, clusterConfig)

            if (onNodeState) {
                stateDirectories.prepareGrafana(stateNode(clusterState))
            }
            if (telemetryRedirect == null) {
                grafana.deploy(clusterState, hosting)
            }

            awaitStack(appliedWorkloads(appliedResources) + grafanaWorkloads(telemetryRedirect))
        }

    /** Waits for [workloads] to finish rolling out, failing the deploy if they do not. */
    private fun awaitRollouts(workloads: List<WorkloadRef>) {
        if (workloads.isEmpty()) return
        k8sService
            .waitForRollouts(workloads, DEFAULT_NAMESPACE, Constants.K8s.OBSERVABILITY_READY_TIMEOUT_SECONDS)
            .getOrElse { exception -> error("$workloads did not finish rolling out: ${exception.message}") }
    }

    /**
     * The node whose host directories hold the backends' and Grafana's state; only host-path storage
     * asks. The K8s API is reached through the workspace kubeconfig, so nothing else needs the node.
     */
    private fun stateNode(clusterState: ClusterState): ClusterHost =
        checkNotNull(clusterState.getControlHost()) {
            "The backends keep their state in host directories on the control node, but the cluster state has no control node."
        }

    /**
     * Reports where the backends, Grafana and kube-state-metrics are placed, before anything is
     * applied, so a node label no node carries is visible before the rollout wait times out.
     */
    private fun reportPlacement(hosting: ObservabilityHosting) {
        val placement = hosting.placement
        eventBus.emit(
            Event.Grafana.PlacementResolved(
                placement.nodeSelector,
                placement.tolerations.map { "${it.key}:${it.operator}:${it.effect}" },
            ),
        )
    }

    /**
     * Hashes every stage, reports which workloads change, then applies the collector and [stages] in
     * order, returning everything applied. Every stage is hashed before any is applied, so a workload
     * reading a ConfigMap this deploy cannot hash fails the deploy before it touches the cluster.
     */
    private fun applyHashed(
        collector: Stage,
        stages: List<Stage>,
        clusterConfig: Map<String, String>,
    ): List<HasMetadata> {
        // Every ConfigMap this deploy knows the contents of, so a workload that reads one from
        // another stage, or the runtime cluster-config, is hashed on it too.
        val knownConfigMaps =
            mapOf(ClusterConfigData.NAME to clusterConfig) +
                (listOf(collector) + stages)
                    .flatMap { it.resources }
                    .filterIsInstance<ConfigMap>()
                    .associate { it.metadata.name to it.data.orEmpty() }

        val hashedStages =
            listOf(collector) + stages.map { it.copy(resources = ConfigHashAnnotator.annotate(it.resources, knownConfigMaps)) }
        val appliedResources = hashedStages.flatMap { it.resources }
        configChangeReport.report(appliedResources, DEFAULT_NAMESPACE)
        for (stage in hashedStages) {
            awaitRollouts(stage.rolledOutFirst)
            applyStage(stage)
        }
        return appliedResources
    }

    /** Grafana is applied by the Grafana deploy service, not a stage, and rolls on a datasource change. */
    private fun grafanaWorkloads(telemetryRedirect: TelemetryRedirect?): List<WorkloadRef> =
        when (telemetryRedirect) {
            null -> listOf(WorkloadRef(WorkloadKind.Deployment, GrafanaManifestBuilder.DEPLOYMENT_NAME))
            else -> emptyList()
        }

    /**
     * Waits for every applied workload's rollout to finish, then for the namespace to reach Ready.
     *
     * Right after a changed workload starts rolling the old pods are still Ready and the replacements
     * may not exist yet, so a pod-readiness check alone reports the stack ready with new pods at 0/1.
     * The readiness gate is namespace-wide and fail-fast: it aborts on CrashLoopBackOff /
     * ImagePullBackOff and on timeout, so `up` never reports success while the observability stack is
     * broken or still coming up. Pods that ran to completion (a kit's Job pods share this namespace)
     * are not awaited.
     */
    private fun awaitStack(workloads: List<WorkloadRef>) {
        k8sService
            .waitForRollouts(workloads, DEFAULT_NAMESPACE, Constants.K8s.OBSERVABILITY_READY_TIMEOUT_SECONDS)
            .getOrElse { exception ->
                error("Observability stack did not finish rolling out: ${exception.message}")
            }
        k8sService
            .waitForPodsReady(Constants.K8s.OBSERVABILITY_READY_TIMEOUT_SECONDS)
            .getOrElse { exception ->
                error("Observability stack did not become ready: ${exception.message}")
            }
    }

    /**
     * The stages to apply after the collector. Collectors and supporting infra deploy in both modes;
     * the local telemetry backends are local-mode only.
     *
     * The host add-on comes first and waits for the core's rollout: on an in-place update the old
     * core pods held hostPorts 4317 and 4318, which the add-on binds on the host. The Pyroscope eBPF
     * agent is privileged and is its own stage, so an environment without privileged pods still
     * runs, and waits on, the Pyroscope server.
     */
    private fun buildStages(
        telemetryRedirect: TelemetryRedirect?,
        cni: CniMode,
        hosting: ObservabilityHosting,
    ): List<Stage> =
        ObservabilityStage.declared(telemetryRedirect).map { stage ->
            Stage(stage.label, manifests.resourcesOf(stage, telemetryRedirect, cni, hosting), stage.requires, rolledOutFirstFor(stage))
        }

    private fun rolledOutFirstFor(stage: ObservabilityStage): List<WorkloadRef> =
        when (stage) {
            ObservabilityStage.OTEL_HOST_COLLECTOR -> listOf(WorkloadRef(WorkloadKind.DaemonSet, Constants.K8s.OTEL_COLLECTOR_APP_LABEL))
            else -> emptyList()
        }

    private fun applyStage(stage: Stage) {
        eventBus.emit(Event.Grafana.LabelResourcesApplying(stage.label))
        for (resource in stage.resources) {
            k8sService.applyResource(resource).getOrElse { exception ->
                error("Failed to apply ${stage.label} ${resource.kind}/${resource.metadata?.name}: ${exception.message}")
            }
        }
        eventBus.emit(Event.Grafana.LabelResourcesApplied(stage.label))
    }

    /**
     * Every Deployment and DaemonSet among [appliedResources]. Deriving the list from what was
     * applied means redirect never waits on a backend it did not deploy.
     */
    private fun appliedWorkloads(appliedResources: List<HasMetadata>): List<WorkloadRef> =
        appliedResources
            .mapNotNull { resource ->
                val name = resource.metadata?.name ?: return@mapNotNull null
                when (resource.kind) {
                    "Deployment" -> WorkloadRef(WorkloadKind.Deployment, name)
                    "DaemonSet" -> WorkloadRef(WorkloadKind.DaemonSet, name)
                    else -> null
                }
            }.distinct()

    /**
     * Creates the cluster-config ConfigMap with runtime values needed by OTel and Tempo
     * ([ClusterConfigData]), returning its data.
     */
    private fun createClusterConfigMap(reading: ObservabilityDeployInputs.Reading): Map<String, String> {
        val configData = ClusterConfigData.of(reading.state, reading.contract.hosting, reading.region)

        k8sService
            .createConfigMap(
                namespace = DEFAULT_NAMESPACE,
                name = ClusterConfigData.NAME,
                data = configData,
                labels = mapOf("app.kubernetes.io/managed-by" to "easy-db-lab"),
            ).getOrElse { exception ->
                // The cluster-config ConfigMap is the source of the `cluster` label every signal
                // ships with (origin identity). If it is missing, metrics/logs/traces/profiles
                // ship unlabeled and two DCs become indistinguishable on one Grafana — so fail
                // fast rather than report a successful `up` over a broken identity mechanism.
                eventBus.emit(Event.Provision.ClusterConfigMapFailed(exception.message ?: exception.toString()))
                error("Failed to create cluster-config ConfigMap: ${exception.message}")
            }
        return configData
    }
}
