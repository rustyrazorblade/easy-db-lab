package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDatasourceConfig
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Service for managing Grafana dashboard manifests.
 *
 * Builds Grafana K8s resources (ConfigMaps and Deployment) programmatically using
 * Fabric8 typed objects and applies them directly via the K8s API.
 */
interface GrafanaDashboardService {
    /**
     * Creates the grafana-datasources ConfigMap.
     *
     * @param controlHost The control node running K3s
     * @return Result indicating success or failure
     */
    fun createDatasourcesConfigMap(controlHost: ClusterHost): Result<Unit>

    /**
     * Builds and applies all Grafana resources (dashboards, provisioning, deployment) to K8s.
     *
     * @param controlHost The control node running K3s
     * @return Result indicating success or failure
     */
    fun uploadDashboards(controlHost: ClusterHost): Result<Unit>

    /**
     * Uploads a single dashboard JSON file to the running Grafana instance via HTTP API.
     *
     * @param file The dashboard JSON file to upload
     * @param controlHost The control node running the Grafana instance
     * @return Result indicating success or failure
     */
    fun installDashboardFromFile(
        file: File,
        controlHost: ClusterHost,
        folderName: String,
    ): Result<Unit>
}

/**
 * Default implementation of GrafanaDashboardService.
 *
 * Uses [GrafanaManifestBuilder] to build typed Fabric8 K8s resources from dashboard JSON
 * resource files and applies them directly via [K8sService.applyResource].
 *
 * @property k8sService Service for K8s operations
 * @property manifestBuilder Builder for Grafana K8s resources
 */
class DefaultGrafanaDashboardService(
    private val k8sService: K8sService,
    private val manifestBuilder: GrafanaManifestBuilder,
    private val eventBus: EventBus,
    private val okHttpClient: OkHttpClient,
) : GrafanaDashboardService {
    companion object {
        private const val DATASOURCES_CONFIGMAP_NAME = "grafana-datasources"
        private const val DEFAULT_NAMESPACE = "default"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    override fun createDatasourcesConfigMap(controlHost: ClusterHost): Result<Unit> {
        val config = GrafanaDatasourceConfig.create()
        val yamlContent = config.toYaml()

        return k8sService.createConfigMap(
            controlHost = controlHost,
            namespace = DEFAULT_NAMESPACE,
            name = DATASOURCES_CONFIGMAP_NAME,
            data = mapOf("datasources.yaml" to yamlContent),
            labels = mapOf("app.kubernetes.io/name" to "grafana"),
        )
    }

    override fun uploadDashboards(controlHost: ClusterHost): Result<Unit> {
        eventBus.emit(Event.Grafana.DatasourcesCreating)
        createDatasourcesConfigMap(controlHost).getOrElse { exception ->
            return Result.failure(
                IllegalStateException("Failed to create Grafana datasources ConfigMap: ${exception.message}", exception),
            )
        }

        val pyroscopeUrl = "http://${controlHost.privateIp}:${Constants.K8s.PYROSCOPE_PORT}"
        val resources = manifestBuilder.buildAllResources(pyroscopeUrl = pyroscopeUrl)
        eventBus.emit(Event.Grafana.ResourcesApplying(resources.size))
        for (resource in resources) {
            val kind = resource.kind
            val name = resource.metadata?.name ?: "unknown"
            eventBus.emit(Event.Grafana.ResourceApplying(kind, name))
            k8sService.applyResource(controlHost, resource).getOrElse { exception ->
                return Result.failure(
                    IllegalStateException("Failed to apply $kind/$name: ${exception.message}", exception),
                )
            }
        }

        eventBus.emit(Event.Grafana.ResourcesApplied)
        return Result.success(Unit)
    }

    override fun installDashboardFromFile(
        file: File,
        controlHost: ClusterHost,
        folderName: String,
    ): Result<Unit> =
        runCatching {
            val folderUid = findOrCreateFolder(controlHost, folderName)
            val dashboardJson = Json.parseToJsonElement(file.readText()).jsonObject
            val title = dashboardJson["title"]?.jsonPrimitive?.contentOrNull ?: "(unknown)"
            val payload =
                buildJsonObject {
                    put("dashboard", dashboardJson)
                    put("overwrite", true)
                    put("folderUid", folderUid)
                }
            val body = Json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE)
            val request =
                Request
                    .Builder()
                    .url("http://${controlHost.privateIp}:${Constants.K8s.GRAFANA_PORT}/api/dashboards/db")
                    .post(body)
                    .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Grafana API returned ${response.code}: ${response.body.string()}")
                }
            }
            eventBus.emit(Event.Grafana.DashboardInstalled(title = title))
        }

    private fun findOrCreateFolder(
        controlHost: ClusterHost,
        name: String,
    ): String {
        val baseUrl = "http://${controlHost.privateIp}:${Constants.K8s.GRAFANA_PORT}"
        val listRequest =
            Request
                .Builder()
                .url("$baseUrl/api/folders")
                .get()
                .build()
        val folders =
            okHttpClient.newCall(listRequest).execute().use { response ->
                if (!response.isSuccessful) error("Failed to list Grafana folders: ${response.code}")
                val bodyStr = requireNotNull(response.body) { "Empty response listing Grafana folders" }.string()
                Json.parseToJsonElement(bodyStr).jsonArray
            }
        folders.forEach { element ->
            val obj = element.jsonObject
            if (obj["title"]?.jsonPrimitive?.contentOrNull == name) {
                return obj["uid"]?.jsonPrimitive?.contentOrNull ?: error("Grafana folder '$name' has no uid")
            }
        }
        val createPayload = buildJsonObject { put("title", name) }
        val createRequest =
            Request
                .Builder()
                .url("$baseUrl/api/folders")
                .post(Json.encodeToString(createPayload).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        return okHttpClient.newCall(createRequest).execute().use { response ->
            if (!response.isSuccessful) error("Failed to create Grafana folder '$name': ${response.code}")
            val bodyStr = requireNotNull(response.body) { "Empty response creating Grafana folder '$name'" }.string()
            Json
                .parseToJsonElement(bodyStr)
                .jsonObject["uid"]
                ?.jsonPrimitive
                ?.contentOrNull ?: error("Grafana folder '$name' response has no uid")
        }
    }
}
