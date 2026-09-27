package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.configuration.Host
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
 * @param config SSH configuration settings
 */
class DefaultSSHConnectionProvider(
    private val config: SSHConfiguration,
) : SSHConnectionProvider,
    KoinComponent {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val connections = SSHConnectionCache(::createNewConnection)
    private val keyPairs: List<KeyPair>
    private val sshClient: SshClient

    init {
        log.info { "Initializing SSH connection provider with key: ${config.keyPath}" }

        // Load key pairs
        val loader = SecurityUtils.getKeyPairResourceParser()
        keyPairs = loader.loadKeyPairs(null, Path(config.keyPath), null).toList()

        // Set up SSH client
        sshClient = SshClient.setUpDefaultClient()
        sshClient.setKeyIdentityProvider(KeyIdentityProvider.wrapKeyPairs(keyPairs))

        // Configure keepalive to prevent session timeouts (heartbeat in milliseconds)
        val heartbeatInterval = Duration.ofSeconds(config.keepAliveIntervalSeconds).toMillis()
        PropertyResolverUtils.updateProperty(sshClient, "heartbeat-interval", heartbeatInterval)

        // Configure session idle timeout (in milliseconds)
        val idleTimeout = Duration.ofMinutes(config.sessionTimeoutMinutes).toMillis()
        PropertyResolverUtils.updateProperty(sshClient, "idle-timeout", idleTimeout)

        sshClient.start()

        log.info { "SSH client initialized successfully with keepalive=${config.keepAliveIntervalSeconds}s" }
    }

    override fun getConnection(host: Host): ISSHClient = connections.get(host)

    /**
     * Create a new SSH connection to a host.
     *
     * @param host The host to connect to
     * @return A new SSH client connected to the host
     */
    private fun createNewConnection(host: Host): ISSHClient {
        log.info { "Creating new SSH connection to ${host.alias} (${host.public})" }

        val session =
            sshClient
                .connect(
                    config.sshUsername,
                    host.public,
                    config.sshPort,
                ).verify(Duration.ofSeconds(config.connectionTimeoutSeconds))
                .session

        session.addPublicKeyIdentity(keyPairs.first())
        session.auth().verify()

        log.info { "SSH connection established to ${host.alias}" }
        return SSHClient(session)
    }

    @Suppress("TooGenericExceptionCaught")
    override fun stop() {
        log.info { "Stopping SSH client and closing ${connections.size()} connections" }

        connections.closeAll()

        try {
            sshClient.stop()
        } catch (e: IOException) {
            log.error(e) { "IO error while stopping SSH client" }
        } catch (e: RuntimeException) {
            log.error(e) { "Runtime error while stopping SSH client" }
        }

        log.info { "SSH client stopped successfully" }
    }
}
