package com.rustyrazorblade.easydblab.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Result of generating a Tailscale auth key, containing both the key value and its ID.
 */
data class TailscaleAuthKey(
    val key: String,
    val id: String,
)

/**
 * Exception thrown when Tailscale API operations fail.
 */
class TailscaleApiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Service for managing Tailscale VPN connections on cluster nodes.
 *
 * Tailscale provides secure mesh VPN access to the cluster. This service handles:
 * - OAuth authentication with Tailscale API to generate ephemeral auth keys
 * - Starting/stopping the Tailscale daemon on control nodes
 * - Advertising VPC CIDR as a subnet route for full cluster access
 *
 * The OAuth flow works as follows:
 * 1. Exchange client credentials for an access token
 * 2. Use the access token to generate an ephemeral auth key
 * 3. Use the auth key to authenticate the Tailscale daemon
 */
interface TailscaleService : AutoCloseable {
    /**
     * Generates an ephemeral Tailscale auth key using OAuth credentials.
     *
     * @param clientId Tailscale OAuth client ID
     * @param clientSecret Tailscale OAuth client secret
     * @param tag Device tag to apply (e.g., "tag:easy-db-lab"). Required for OAuth-based auth keys.
     * @return TailscaleAuthKey containing both the key value and its ID
     * @throws TailscaleApiException if the API request fails
     */
    fun generateAuthKey(
        clientId: String,
        clientSecret: String,
        tag: String,
    ): TailscaleAuthKey

    /**
     * Deletes a Tailscale auth key by ID.
     *
     * @param clientId Tailscale OAuth client ID
     * @param clientSecret Tailscale OAuth client secret
     * @param keyId The auth key ID to delete
     * @throws TailscaleApiException if the API request fails
     */
    fun deleteAuthKey(
        clientId: String,
        clientSecret: String,
        keyId: String,
    )

    /**
     * Starts Tailscale on the specified host and authenticates with the provided auth key.
     *
     * This method:
     * 1. Starts the tailscaled daemon
     * 2. Runs `tailscale up` with the auth key and subnet routes
     *
     * @param host The host to start Tailscale on
     * @param authKey The ephemeral auth key from generateAuthKey()
     * @param hostname The hostname to register with Tailscale (typically host.alias)
     * @param cidr The VPC CIDR to advertise as a subnet route
     * @return Result indicating success or failure
     */
    fun startTailscale(
        host: Host,
        authKey: String,
        hostname: String,
        cidr: String,
    ): Result<Unit>

    /**
     * Stops Tailscale on the specified host.
     *
     * This method runs `tailscale down` and stops the daemon.
     *
     * @param host The host to stop Tailscale on
     * @return Result indicating success or failure
     */
    fun stopTailscale(host: Host): Result<Unit>

    /**
     * Gets the Tailscale connection status on the specified host.
     *
     * @param host The host to check
     * @return Result containing the status output or failure details
     */
    fun getStatus(host: Host): Result<String>

    /**
     * Checks if Tailscale is currently connected on the specified host.
     *
     * @param host The host to check
     * @return Result containing true if connected, false otherwise
     */
    fun isConnected(host: Host): Result<Boolean>
}

/**
 * Default implementation of TailscaleService using OkHttp for API calls
 * and RemoteOperationsService for SSH commands.
 */
class DefaultTailscaleService(
    private val remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
) : TailscaleService {
    private val httpClient: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(Constants.Tailscale.CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(Constants.Tailscale.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    override fun generateAuthKey(
        clientId: String,
        clientSecret: String,
        tag: String,
    ): TailscaleAuthKey {
        log.info { "Generating Tailscale auth key with tag: $tag" }

        // Step 1: Exchange client credentials for access token
        val accessToken = getAccessToken(clientId, clientSecret)

        // Step 2: Generate ephemeral auth key
        return createAuthKey(accessToken, tag)
    }

    override fun deleteAuthKey(
        clientId: String,
        clientSecret: String,
        keyId: String,
    ) {
        log.info { "Deleting Tailscale auth key: $keyId" }

        val accessToken = getAccessToken(clientId, clientSecret)

        val request =
            Request
                .Builder()
                .url("${Constants.Tailscale.AUTH_KEYS_ENDPOINT}/$keyId")
                .header("Authorization", "Bearer $accessToken")
                .delete()
                .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body.string()
                throw TailscaleApiException("Failed to delete auth key $keyId: ${response.code} - $errorBody")
            }
        }

        log.info { "Tailscale auth key $keyId deleted successfully" }
    }

    /**
     * Exchanges OAuth client credentials for an access token.
     */
    private fun getAccessToken(
        clientId: String,
        clientSecret: String,
    ): String {
        val requestBody =
            FormBody
                .Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .build()

        val request =
            Request
                .Builder()
                .url(Constants.Tailscale.OAUTH_TOKEN_ENDPOINT)
                .post(requestBody)
                .build()

        return httpClient.newCall(request).execute().use { response ->
            val body = parseSuccessfulResponse(response, "get OAuth access token")
            val tokenResponse: Map<String, Any> = objectMapper.readValue(body)
            tokenResponse["access_token"] as? String
                ?: throw TailscaleApiException("No access_token in response")
        }
    }

    /**
     * Creates an ephemeral auth key using the access token.
     */
    private fun createAuthKey(
        accessToken: String,
        tag: String,
    ): TailscaleAuthKey {
        val keyRequest =
            mapOf(
                "capabilities" to
                    mapOf(
                        "devices" to
                            mapOf(
                                "create" to
                                    mapOf(
                                        "reusable" to false,
                                        "ephemeral" to true,
                                        "preauthorized" to true,
                                        "tags" to listOf(tag),
                                    ),
                            ),
                    ),
                "expirySeconds" to Constants.Tailscale.AUTH_KEY_EXPIRY_SECONDS,
            )

        val requestBody =
            objectMapper
                .writeValueAsString(keyRequest)
                .toRequestBody("application/json".toMediaType())

        val request =
            Request
                .Builder()
                .url(Constants.Tailscale.AUTH_KEYS_ENDPOINT)
                .header("Authorization", "Bearer $accessToken")
                .post(requestBody)
                .build()

        return httpClient.newCall(request).execute().use { response ->
            val body = parseSuccessfulResponse(response, "create auth key")
            val keyResponse: Map<String, Any> = objectMapper.readValue(body)
            val key =
                keyResponse["key"] as? String
                    ?: throw TailscaleApiException("No key in response")
            val id =
                keyResponse["id"] as? String
                    ?: throw TailscaleApiException("No id in response")
            TailscaleAuthKey(key = key, id = id)
        }
    }

    /**
     * Parses and validates an HTTP response, returning the body on success.
     * @throws TailscaleApiException if the response indicates failure or has no body
     */
    private fun parseSuccessfulResponse(
        response: Response,
        operation: String,
    ): String {
        if (!response.isSuccessful) {
            val errorBody = response.body.string()
            throw TailscaleApiException("Failed to $operation: ${response.code} - $errorBody")
        }
        return response.body.string()
    }

    override fun startTailscale(
        host: Host,
        authKey: String,
        hostname: String,
        cidr: String,
    ): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Tailscale.DaemonStarting(host.alias))

            // Start the daemon
            remoteOps.executeRemotely(
                host,
                "sudo systemctl start tailscaled",
            )

            // Give the daemon a moment to initialize
            Thread.sleep(Constants.Tailscale.DAEMON_STARTUP_DELAY_MS)

            eventBus.emit(Event.Tailscale.Authenticating(host.alias))

            // Authenticate and advertise routes
            // Note: secret=true to prevent auth key from being logged
            remoteOps.executeRemotely(
                host,
                "sudo tailscale up --authkey=$authKey --hostname=$hostname --advertise-routes=$cidr --accept-routes",
                secret = true,
            )

            log.info { "Tailscale started successfully on ${host.alias}" }
            eventBus.emit(Event.Tailscale.Connected(host.alias))
        }

    override fun stopTailscale(host: Host): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Tailscale.Stopping(host.alias))

            // Disconnect from Tailscale network
            remoteOps.executeRemotely(
                host,
                "sudo tailscale down",
            )

            // Stop the daemon
            remoteOps.executeRemotely(
                host,
                "sudo systemctl stop tailscaled",
            )

            log.info { "Tailscale stopped on ${host.alias}" }
            eventBus.emit(Event.Tailscale.Stopped(host.alias))
        }

    override fun getStatus(host: Host): Result<String> =
        runCatching {
            val response =
                remoteOps.executeRemotely(
                    host,
                    "sudo tailscale status",
                )
            response.text
        }

    override fun isConnected(host: Host): Result<Boolean> =
        runCatching {
            val response =
                remoteOps.executeRemotely(
                    host,
                    "sudo tailscale status --json",
                    output = false,
                )

            if (response.text.isBlank()) {
                return@runCatching false
            }

            val status: Map<String, Any> = objectMapper.readValue(response.text)
            val backendState = status["BackendState"] as? String
            backendState == "Running"
        }

    override fun close() {
        log.info { "Shutting down TailscaleService OkHttp client" }
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}
