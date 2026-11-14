package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.services.access.ClusterServices
import com.rustyrazorblade.easydblab.services.access.ServiceAccess
import com.rustyrazorblade.easydblab.services.access.baseUrl
import com.rustyrazorblade.easydblab.services.access.forEndpoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The running Grafana's HTTP API, as easy-db-lab uses it: dashboards, folders and annotations.
 *
 * It only talks to a Grafana that is already up. Getting Grafana and its datasources onto the
 * cluster is [GrafanaDeployService]'s job; this client is what kit `start`, `grafana install`,
 * `grafana annotate`, the annotation backup and the `down` annotation mirror call afterwards.
 */
interface GrafanaClient : GrafanaAnnotationSource {
    /**
     * Uploads one dashboard into [folderName] of the running Grafana. A dashboard whose uid already
     * exists is overwritten, and moved into [folderName].
     */
    fun installDashboard(
        dashboard: JsonObject,
        folderName: String,
    ): Result<Unit>

    /**
     * Creates a Grafana annotation via `POST /api/annotations`.
     *
     * It throws (it does NOT return a failure Result) on a non-2xx response or an unreachable
     * endpoint, with a message naming the Grafana annotation endpoint, so `grafana annotate` fails
     * non-zero when Grafana cannot be reached.
     *
     * @return The Grafana response carrying the created annotation id.
     * @throws IllegalStateException on a non-2xx response or an unreachable endpoint.
     */
    fun createAnnotation(annotation: GrafanaAnnotationRequest): GrafanaAnnotationResponse

    /**
     * Fetches all Grafana annotations via `GET /api/annotations`.
     *
     * Requests an explicit high `limit` ([Constants.Grafana.ANNOTATION_FETCH_LIMIT]) so the response
     * is not silently capped at Grafana's default of 100, and returns the body verbatim so the
     * backup preserves exactly what Grafana returns. It throws on a non-2xx response, an unreachable
     * endpoint, or a response that fills the requested limit (a possible truncation).
     */
    override fun fetchAnnotations(): String
}

/**
 * Default [GrafanaClient]: each call opens Grafana through the service-access seam and sends its
 * requests on a client carrying the route that endpoint names, so it never keeps a tunnel port.
 *
 * @property eventBus reports an installed dashboard
 * @property access opens the Grafana Service
 * @property httpClientFactory the base HTTP client the route is applied to
 */
class DefaultGrafanaClient(
    private val eventBus: EventBus,
    private val access: ServiceAccess,
    private val httpClientFactory: HttpClientFactory,
) : GrafanaClient {
    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * JSON codec for annotation payloads. Null fields are dropped so a global annotation sends
         * only the fields it sets, and unknown fields on decode are ignored for forward tolerance.
         */
        private val annotationJson =
            Json {
                explicitNulls = false
                ignoreUnknownKeys = true
            }
    }

    /** Grafana's base URL and the client that reaches it, opened through the seam for one call. */
    private class Connection(
        val baseUrl: String,
        val client: OkHttpClient,
    )

    private fun connect(): Connection {
        val endpoint = access.open(ClusterServices.GRAFANA)
        return Connection(endpoint.baseUrl(), httpClientFactory.createClient().forEndpoint(endpoint))
    }

    override fun installDashboard(
        dashboard: JsonObject,
        folderName: String,
    ): Result<Unit> =
        runCatching {
            val grafana = connect()
            val folderUid = findOrCreateFolder(grafana, folderName)
            val title = dashboard["title"]?.jsonPrimitive?.contentOrNull ?: "(unknown)"
            val payload =
                buildJsonObject {
                    put("dashboard", dashboard)
                    put("overwrite", true)
                    put("folderUid", folderUid)
                }
            val request =
                Request
                    .Builder()
                    .url("${grafana.baseUrl}/api/dashboards/db")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            grafana.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Grafana API returned ${response.code}: ${response.body.string()}")
                }
            }
            eventBus.emit(Event.Grafana.DashboardInstalled(title = title))
        }

    override fun createAnnotation(annotation: GrafanaAnnotationRequest): GrafanaAnnotationResponse {
        val grafana = connect()
        val endpoint = annotationsEndpoint(grafana)
        val body = annotationJson.encodeToString(annotation).toRequestBody(JSON_MEDIA_TYPE)
        val request =
            Request
                .Builder()
                .url(endpoint)
                .post(body)
                .build()
        val bodyStr =
            try {
                grafana.client.newCall(request).execute().use { response ->
                    val responseBody = response.body.string()
                    if (!response.isSuccessful) {
                        error("Grafana annotation API at $endpoint returned ${response.code}: $responseBody")
                    }
                    responseBody
                }
            } catch (e: IOException) {
                throw IllegalStateException("Failed to reach Grafana annotation API at $endpoint: ${e.message}", e)
            }
        return annotationJson.decodeFromString<GrafanaAnnotationResponse>(bodyStr)
    }

    override fun fetchAnnotations(): String {
        val grafana = connect()
        val endpoint = annotationsEndpoint(grafana)
        // Grafana defaults the annotations limit to 100 and offers no pagination cursor, so a request
        // without an explicit limit would capture at most 100 annotations. Ask for a high explicit
        // limit so the backup captures every annotation on a normal cluster.
        val url =
            endpoint
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("limit", Constants.Grafana.ANNOTATION_FETCH_LIMIT.toString())
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .build()
        val responseBody =
            try {
                grafana.client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful) {
                        error("Grafana annotation API at $endpoint returned ${response.code}: $body")
                    }
                    body
                }
            } catch (e: IOException) {
                throw IllegalStateException("Failed to reach Grafana annotation API at $endpoint: ${e.message}", e)
            }

        // If the returned array fills the requested limit exactly, more annotations may exist that this
        // single call did not return. Treating that as a complete backup would silently drop data, so
        // fail loudly instead of reporting a truncated capture as success.
        val count = runCatching { Json.parseToJsonElement(responseBody).jsonArray.size }.getOrDefault(0)
        if (count >= Constants.Grafana.ANNOTATION_FETCH_LIMIT) {
            error(
                "Grafana annotation API at $endpoint returned $count annotations, the maximum this request " +
                    "asked for; the backup would be truncated. Raise Constants.Grafana.ANNOTATION_FETCH_LIMIT.",
            )
        }
        return responseBody
    }

    private fun annotationsEndpoint(grafana: Connection): String = "${grafana.baseUrl}/api/annotations"

    private fun findOrCreateFolder(
        grafana: Connection,
        name: String,
    ): String {
        val listRequest =
            Request
                .Builder()
                .url("${grafana.baseUrl}/api/folders")
                .get()
                .build()
        val folders =
            grafana.client.newCall(listRequest).execute().use { response ->
                if (!response.isSuccessful) error("Failed to list Grafana folders: ${response.code}")
                Json.parseToJsonElement(response.body.string()).jsonArray
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
                .url("${grafana.baseUrl}/api/folders")
                .post(Json.encodeToString(createPayload).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        return grafana.client.newCall(createRequest).execute().use { response ->
            if (!response.isSuccessful) error("Failed to create Grafana folder '$name': ${response.code}")
            Json
                .parseToJsonElement(response.body.string())
                .jsonObject["uid"]
                ?.jsonPrimitive
                ?.contentOrNull ?: error("Grafana folder '$name' response has no uid")
        }
    }
}
