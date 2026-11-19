package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.runCatchingException
import com.rustyrazorblade.easydblab.ssh.ISSHClient
import com.rustyrazorblade.easydblab.ssh.SSHClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.sshd.client.SshClient
import org.apache.sshd.common.PropertyResolverUtils
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.koin.core.component.KoinComponent
import java.io.IOException
import java.security.KeyPair
import java.time.Duration
import kotlin.io.path.Path

/**
 * Default implementation of SSHConnectionProvider.
 * Manages a pool of SSH connections to multiple hosts. The pool is an [SSHConnectionCache], so
 * threads that ask for the same host at the same time share one connection.
 *
 * The key (from the profile) is loaded and the MINA client started on the first connection, not
 * when the provider is built: commands in a local workspace, which has no profile, build it through
 * `remoteOps` and never reach a node.
 *
 * @param config SSH configuration settings
 * @param route decides the address and port each host is dialed at, which is how the profile's
 *   SSH transport reaches the in-process client
 */
class DefaultSSHConnectionProvider(
    private val config: SSHConfiguration,
    private val route: SshRoute,
) : SSHConnectionProvider,
    KoinComponent {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val connections = SSHConnectionCache(::createNewConnection)

    /** The loaded key and the started client, made by the first connection. */
    private class Started(
        val keyPairs: List<KeyPair>,
        val sshClient: SshClient,
    )

    private val started = lazy(::start)

    private fun start(): Started {
        log.info { "Initializing SSH connection provider with key: ${config.keyPath}" }

        val loader = SecurityUtils.getKeyPairResourceParser()
        val keyPairs = loader.loadKeyPairs(null, Path(config.keyPath), null).toList()

        val sshClient = SshClient.setUpDefaultClient()
        sshClient.setKeyIdentityProvider(KeyIdentityProvider.wrapKeyPairs(keyPairs))

        // Configure keepalive to prevent session timeouts (heartbeat in milliseconds)
        val heartbeatInterval = Duration.ofSeconds(config.keepAliveIntervalSeconds).toMillis()
        PropertyResolverUtils.updateProperty(sshClient, "heartbeat-interval", heartbeatInterval)

        // Configure session idle timeout (in milliseconds)
        val idleTimeout = Duration.ofMinutes(config.sessionTimeoutMinutes).toMillis()
        PropertyResolverUtils.updateProperty(sshClient, "idle-timeout", idleTimeout)

        sshClient.start()

        log.info { "SSH client initialized successfully with keepalive=${config.keepAliveIntervalSeconds}s" }
        return Started(keyPairs, sshClient)
    }

    override fun getConnection(host: Host): ISSHClient = connections.get(host)

    override fun discard(host: Host) {
        connections.drop(host)
        route.invalidate(host)
    }

    /**
     * Create a new SSH connection to a host.
     *
     * @param host The host to connect to
     * @return A new SSH client connected to the host
     */
    private fun createNewConnection(host: Host): ISSHClient {
        val (keyPairs, sshClient) = started.value.let { it.keyPairs to it.sshClient }
        val endpoint = route.endpoint(host)
        log.info { "Creating new SSH connection to ${host.alias} (${endpoint.address}:${endpoint.port})" }

        // A path that fails to connect, exchange keys or authenticate is reported to the route,
        // so a retry does not dial the same one: an SSM forward can accept TCP and then carry
        // nothing, and every retry through it would wait out the full auth timeout.
        try {
            val session =
                sshClient
                    .connect(
                        config.sshUsername,
                        endpoint.address,
                        endpoint.port,
                    ).verify(Duration.ofSeconds(config.connectionTimeoutSeconds))
                    .session
            try {
                session.addPublicKeyIdentity(keyPairs.first())
                // The route bounds the wait when its transport has a tighter one than MINA's default.
                val auth = session.auth()
                route.authTimeout?.let { auth.verify(it) } ?: auth.verify()
            } catch (e: IOException) {
                session.close(true)
                throw e
            }
            log.info { "SSH connection established to ${host.alias}" }
            return SSHClient(session)
        } catch (e: IOException) {
            log.warn {
                "SSH connection to ${host.alias} via ${endpoint.address}:${endpoint.port} failed (${e.message}); retiring that path"
            }
            route.invalidate(host)
            throw e
        }
    }

    override fun stop() {
        log.info { "Stopping SSH client and closing ${connections.size()} connections" }

        connections.closeAll()

        // Logged, not thrown: the route below must still close. A client never started has nothing to stop.
        if (started.isInitialized()) {
            runCatchingException { started.value.sshClient.stop() }
                .onFailure { e -> log.error(e) { "Error while stopping SSH client" } }
        }

        // Closed after the sessions above, since a session may be riding a tunnel the route started.
        route.close()

        log.info { "SSH client stopped successfully" }
    }
}
