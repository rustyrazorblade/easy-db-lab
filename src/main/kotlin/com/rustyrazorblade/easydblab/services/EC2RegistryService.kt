package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files

/**
 * AWS EC2 implementation of RegistryService for configuring TLS on container registries.
 *
 * This service generates self-signed TLS certificates on the control node and distributes
 * them to all cluster nodes via S3. It configures containerd on each node to trust the
 * registry's certificate, enabling secure HTTPS communication.
 *
 * The implementation uses SSH to upload and execute shell scripts on remote hosts,
 * and relies on the AWS CLI (available on all nodes via IAM instance profile) for S3 operations.
 *
 * @property remoteOps Service for executing SSH commands on remote hosts
 */
class EC2RegistryService(
    private val remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
) : RegistryService {
    private val log = KotlinLogging.logger {}

    companion object {
        private const val GENERATE_CERT_SCRIPT = "generate_registry_cert.sh"
        private const val CONFIGURE_TLS_SCRIPT = "configure_registry_tls.sh"
        private const val REMOTE_SCRIPT_DIR = "/tmp"
    }

    /**
     * Generates a self-signed TLS certificate on the control node and uploads it to S3.
     *
     * Creates a certificate with the control node's private IP as both the Common Name
     * and Subject Alternative Name. The certificate is valid for 365 days.
     *
     * @param controlHost The control node where the certificate will be generated
     * @param s3Bucket The S3 bucket name where the certificate will be uploaded
     */
    override fun generateAndUploadCert(
        controlHost: Host,
        s3Bucket: String,
    ) {
        eventBus.emit(Event.Registry.CertGenerating(controlHost.alias))

        val registryIp = controlHost.private
        val certDir = Constants.Registry.CERT_DIR
        val s3Path = Constants.Registry.S3_CERT_PATH

        // Upload and execute the certificate generation script
        val scriptPath = uploadScript(controlHost, GENERATE_CERT_SCRIPT)
        remoteOps.executeRemotely(
            controlHost,
            "bash $scriptPath '$registryIp' '$certDir' '$s3Bucket' '$s3Path'",
        )

        log.info { "Generated TLS certificate on ${controlHost.alias}" }
        eventBus.emit(Event.Registry.CertUploaded)
        log.info { "Uploaded certificate to s3://$s3Bucket/$s3Path" }
    }

    /**
     * Configures containerd and K3s on a node to trust the registry's TLS certificate.
     *
     * Downloads the CA certificate from S3 and creates the containerd certificate
     * configuration for the registry. Also configures /etc/rancher/k3s/registries.yaml
     * with the HTTPS registry endpoint and TLS configuration. Restarts containerd if running.
     *
     * @param host The node to configure
     * @param registryHost The private IP address of the registry (control node)
     * @param s3Bucket The S3 bucket containing the CA certificate
     */
    override fun configureTlsOnNode(
        host: Host,
        registryHost: String,
        s3Bucket: String,
    ) {
        eventBus.emit(Event.Registry.TlsConfiguring(host.alias))

        val registryPort = Constants.Registry.PORT
        val s3Path = Constants.Registry.S3_CERT_PATH

        // Upload and execute the TLS configuration script
        val scriptPath = uploadScript(host, CONFIGURE_TLS_SCRIPT)
        remoteOps.executeRemotely(
            host,
            "bash $scriptPath '$registryHost' '$registryPort' '$s3Bucket' '$s3Path'",
        )

        log.info { "Configured containerd and K3s on ${host.alias} to trust registry at $registryHost:$registryPort" }
    }

    /**
     * Uploads a script from resources to the remote host.
     *
     * @param host The host to upload to
     * @param scriptName The name of the script in resources
     * @return The remote path where the script was uploaded
     */
    private fun uploadScript(
        host: Host,
        scriptName: String,
    ): String {
        val resourcePath = "/com/rustyrazorblade/easydblab/commands/$scriptName"
        val scriptContent =
            this::class.java.getResourceAsStream(resourcePath)
                ?: error("Script not found: $resourcePath")

        val tempFile = Files.createTempFile("registry-", "-$scriptName")
        try {
            scriptContent.use { input ->
                Files.newOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            val remotePath = "$REMOTE_SCRIPT_DIR/$scriptName"
            remoteOps.upload(host, tempFile, remotePath)
            return remotePath
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}
