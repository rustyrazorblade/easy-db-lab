package com.rustyrazorblade.easydblab.commands.tailscale

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.TailscaleApiException
import com.rustyrazorblade.easydblab.services.TailscaleService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Start Tailscale VPN on the control node and authenticate.
 *
 * This command:
 * 1. Generates an ephemeral auth key using OAuth credentials
 * 2. Starts the Tailscale daemon on the control node
 * 3. Authenticates with the auth key and advertises VPC CIDR as a subnet route
 *
 * After running this command, you can access the control node via its Tailscale IP,
 * and through the advertised route, access all other nodes in the VPC.
 *
 * OAuth credentials can be provided via:
 * - CLI arguments (--client-id, --client-secret)
 * - User configuration (setup-profile)
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "start",
    description = ["Start Tailscale VPN on the control node"],
)
class TailscaleStart : PicoBaseCommand() {
    private val tailscaleService: TailscaleService by inject()
    private val user: User by inject()

    @Option(
        names = ["--client-id"],
        description = ["Tailscale OAuth client ID (overrides config)"],
    )
    var clientId: String? = null

    @Option(
        names = ["--client-secret"],
        description = ["Tailscale OAuth client secret (overrides config)"],
    )
    var clientSecret: String? = null

    @Option(
        names = ["--tag"],
        description = ["Tailscale device tag (default: tag:easy-db-lab)"],
    )
    var tag: String? = null

    override fun execute() {
        val credentials = resolveCredentials() ?: return
        val controlHost = getControlHostOrReturn() ?: return
        val host = controlHost.toHost()

        if (checkAlreadyConnected(host, controlHost.alias)) return

        startTailscaleConnection(credentials, host, controlHost)
    }

    private fun resolveCredentials(): TailscaleCredentials? {
        val resolvedClientId = clientId ?: user.tailscaleClientId
        val resolvedClientSecret = clientSecret ?: user.tailscaleClientSecret
        val resolvedTag = tag ?: user.tailscaleTag.ifEmpty { Constants.Tailscale.DEFAULT_DEVICE_TAG }

        if (resolvedClientId.isBlank() || resolvedClientSecret.isBlank()) {
            showMissingCredentialsError()
            return null
        }

        return TailscaleCredentials(resolvedClientId, resolvedClientSecret, resolvedTag)
    }

    private fun showMissingCredentialsError() {
        eventBus.emit(
            Event.Tailscale.CredentialMissing(
                """
                Tailscale OAuth credentials not configured.

                Please provide credentials via:
                1. CLI arguments: --client-id and --client-secret
                2. Setup profile: easy-db-lab setup-profile

                To get OAuth credentials:
                1. Go to https://login.tailscale.com/admin/settings/oauth
                2. Generate an OAuth client with "Devices: write" scope
                3. Copy the client ID and secret
                """.trimIndent(),
            ),
        )
    }

    private fun getControlHostOrReturn(): ClusterHost? {
        val controlHost = clusterState.getControlHost()
        if (controlHost == null) {
            eventBus.emit(Event.Tailscale.NoControlNode)
        }
        return controlHost
    }

    private fun checkAlreadyConnected(
        host: Host,
        alias: String,
    ): Boolean {
        val isConnected = tailscaleService.isConnected(host).getOrElse { false }
        if (isConnected) {
            eventBus.emit(Event.Tailscale.AlreadyConnected(alias))
            tailscaleService.getStatus(host).onSuccess { status ->
                eventBus.emit(Event.Tailscale.CurrentStatusOutput(status))
            }
        }
        return isConnected
    }

    private fun startTailscaleConnection(
        credentials: TailscaleCredentials,
        host: Host,
        controlHost: ClusterHost,
    ) {
        val cidr = clusterState.initConfig?.cidr ?: Constants.Vpc.DEFAULT_CIDR

        try {
            eventBus.emit(Event.Tailscale.GeneratingAuthKey)
            val authKeyResult =
                tailscaleService.generateAuthKey(
                    credentials.clientId,
                    credentials.clientSecret,
                    credentials.tag,
                )

            clusterState.updateTailscaleAuthKeyId(authKeyResult.id)
            clusterStateManager.save(clusterState)

            tailscaleService.startTailscale(host, authKeyResult.key, controlHost.alias, cidr).getOrThrow()
            showSuccessMessage(controlHost.alias, cidr)
            showCurrentStatus(host)
        } catch (e: TailscaleApiException) {
            eventBus.emit(Event.Tailscale.StartFailed(e.message ?: "unknown error"))
            if (e.message?.contains("tags") == true) {
                eventBus.emit(Event.Tailscale.TagConfigWarning(credentials.tag))
            }
        }
    }

    private fun showSuccessMessage(
        alias: String,
        cidr: String,
    ) {
        eventBus.emit(Event.Tailscale.StartedSuccessfully)
        eventBus.emit(Event.Tailscale.ConnectionDetails(alias, cidr))
    }

    private fun showCurrentStatus(host: Host) {
        tailscaleService.getStatus(host).onSuccess { status ->
            eventBus.emit(Event.Tailscale.CurrentStatusLabel)
            eventBus.emit(Event.Tailscale.CurrentStatusOutput(status))
        }
    }

    private data class TailscaleCredentials(
        val clientId: String,
        val clientSecret: String,
        val tag: String,
    )
}
