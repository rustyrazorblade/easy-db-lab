package com.rustyrazorblade.easydblab.commands.tailscale

import com.github.ajalt.mordant.TermColors
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
        with(TermColors()) {
            eventBus.emit(
                Event.Error(
                    red(
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
                ),
            )
        }
    }

    private fun getControlHostOrReturn(): ClusterHost? {
        val controlHost = clusterState.getControlHost()
        if (controlHost == null) {
            with(TermColors()) {
                eventBus.emit(
                    Event.Error(red("No control node found. Ensure your cluster is running with 'easy-db-lab up'.")),
                )
            }
        }
        return controlHost
    }

    private fun checkAlreadyConnected(
        host: Host,
        alias: String,
    ): Boolean {
        val isConnected = tailscaleService.isConnected(host).getOrElse { false }
        if (isConnected) {
            with(TermColors()) {
                eventBus.emit(Event.Message(yellow("Tailscale is already connected on $alias.")))
            }
            tailscaleService.getStatus(host).onSuccess { status ->
                eventBus.emit(Event.Message(status))
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
            eventBus.emit(Event.Message("Generating Tailscale auth key..."))
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
            with(TermColors()) {
                eventBus.emit(Event.Error(red("Failed to start Tailscale: ${e.message}")))
                if (e.message?.contains("tags") == true) {
                    eventBus.emit(
                        Event.Message(
                            yellow(
                                """

                                The tag '${credentials.tag}' must be configured in your Tailscale ACL.

                                To fix this:
                                1. Go to https://login.tailscale.com/admin/acls
                                2. Add this to your ACL policy:
                                   "tagOwners": {
                                     "${credentials.tag}": ["autogroup:admin"]
                                   }
                                3. Ensure your OAuth client has permission to use this tag

                                Or use a different tag: --tag tag:your-existing-tag
                                """.trimIndent(),
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun showSuccessMessage(
        alias: String,
        cidr: String,
    ) {
        with(TermColors()) {
            eventBus.emit(Event.Message(green("\nTailscale started successfully!")))
            eventBus.emit(
                Event.Message(
                    """

                    The control node ($alias) is now accessible via Tailscale.
                    Subnet route advertised: $cidr

                    NOTE: You may need to approve the subnet route in the Tailscale admin console:
                    https://login.tailscale.com/admin/machines

                    Once approved, you can access all cluster nodes through the control node's
                    Tailscale connection using their private IPs.
                    """.trimIndent(),
                ),
            )
        }
    }

    private fun showCurrentStatus(host: Host) {
        tailscaleService.getStatus(host).onSuccess { status ->
            eventBus.emit(Event.Message("\nCurrent status:"))
            eventBus.emit(Event.Message(status))
        }
    }

    private data class TailscaleCredentials(
        val clientId: String,
        val clientSecret: String,
        val tag: String,
    )
}
