package com.rustyrazorblade.easydblab.providers.ssm

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.providers.aws.AWSCredentialsManager
import com.rustyrazorblade.easydblab.shellQuote

/**
 * The AWS identity the AWS CLI uses when it opens a Session Manager session.
 *
 * It must be the identity the profile's SDK clients already use, so an operator who can provision
 * a cluster can also reach it. The two cases mirror how the profile itself authenticates.
 */
sealed interface SsmCliCredentials {
    /** A named AWS CLI profile, SSO-backed or not; the CLI refreshes it on its own. */
    data class NamedProfile(
        val name: String,
    ) : SsmCliCredentials

    /**
     * A shared-credentials file holding the profile's static keys under
     * [Constants.AWS.CREDENTIALS_FILE_PROFILE]. Pointing the CLI at a file, rather than passing
     * the keys as environment variables, keeps them out of every generated `sshConfig`. The CLI
     * reads no config file in this case, so nothing in the operator's own AWS config applies.
     */
    data class CredentialsFile(
        val path: String,
    ) : SsmCliCredentials

    companion object {
        /**
         * Chooses the credentials for [user]: its named AWS profile when set, otherwise the file
         * [credentialsManager] writes. The file is only touched in that case, because reading its
         * path writes it to disk.
         */
        fun forUser(
            user: User,
            credentialsManager: AWSCredentialsManager,
        ): SsmCliCredentials =
            if (user.awsProfile.isNotEmpty()) {
                NamedProfile(user.awsProfile)
            } else {
                CredentialsFile(credentialsManager.credentialsPath)
            }
    }
}

/**
 * One `aws ssm start-session` invocation: its argv and the environment it needs on top of the
 * caller's own.
 */
data class SsmSessionCommand(
    val argv: List<String>,
    val environment: Map<String, String>,
) {
    /**
     * Renders the invocation as a single shell command line, as an ssh_config `ProxyCommand`
     * requires. Every word is shell-quoted; ssh's own `%p` token survives because `%` is a
     * shell-safe character.
     */
    fun toShellCommand(): String {
        val envPrefix =
            if (environment.isEmpty()) {
                emptyList()
            } else {
                listOf("env") + environment.toSortedMap().map { (name, value) -> "$name=${value.shellQuote()}" }
            }
        return (envPrefix + argv.map { it.shellQuote() }).joinToString(" ")
    }
}

/**
 * Builds the `aws ssm start-session` command lines the SSM SSH transport runs.
 *
 * Both SSH paths take their command lines from here: the `ProxyCommand` in `sshConfig`, used by
 * OpenSSH, and the port forwards that carry the in-process SSH client. A single builder means the
 * two paths cannot drift on region, credentials, or document.
 *
 * @param region the AWS region the cluster's instances live in
 * @param credentials resolved on first use, because the static-key case writes a file
 * @param awsExecutable the AWS CLI to run; a parameter so tests can substitute a stub script
 * @param sshProxyWrapper the path of the `edl-ssm-proxy` wrapper the ProxyCommand runs the session
 *   through, resolved on first use (it installs the wrapper); null runs the CLI directly
 */
class SsmSessionCommandBuilder(
    private val region: String,
    credentials: () -> SsmCliCredentials,
    private val awsExecutable: String = Constants.Ssm.AWS_CLI,
    sshProxyWrapper: (() -> String)? = null,
) {
    private val credentials: SsmCliCredentials by lazy(credentials)
    private val sshProxyWrapper: String? by lazy { sshProxyWrapper?.invoke() }

    /**
     * A session that bridges the process's stdin/stdout to [instanceId], for an ssh_config
     * `ProxyCommand`. The port is ssh's `%p` token, which ssh replaces with the target port.
     */
    fun sshSession(instanceId: String): SsmSessionCommand =
        build(instanceId, Constants.Ssm.SSH_SESSION_DOCUMENT, "portNumber=$SSH_CONFIG_PORT_TOKEN").let { command ->
            // The wrapper ends the CLI's process tree when ssh goes away; see SsmProxyWrapper.
            sshProxyWrapper?.let { command.copy(argv = listOf(it) + command.argv) } ?: command
        }

    /** A session that forwards [localPort] on the loopback interface to [remotePort] on [instanceId]. */
    fun portForwardSession(
        instanceId: String,
        remotePort: Int,
        localPort: Int,
    ): SsmSessionCommand =
        build(
            instanceId,
            Constants.Ssm.PORT_FORWARD_DOCUMENT,
            "portNumber=$remotePort,localPortNumber=$localPort",
        )

    private fun build(
        instanceId: String,
        document: String,
        parameters: String,
    ): SsmSessionCommand {
        val argv =
            listOf(
                awsExecutable,
                "ssm",
                "start-session",
                "--target",
                instanceId,
                "--document-name",
                document,
                "--parameters",
                parameters,
                "--region",
                region,
            )

        return when (val creds = credentials) {
            is SsmCliCredentials.NamedProfile ->
                SsmSessionCommand(argv + listOf("--profile", creds.name), emptyMap())
            // The operator's ~/.aws/config is set aside too: a [default] section there can carry
            // sso_*, role_arn or credential_process settings, which the CLI would otherwise apply on
            // top of the static keys in the file, and sign as some other identity.
            is SsmCliCredentials.CredentialsFile ->
                SsmSessionCommand(
                    argv + listOf("--profile", Constants.AWS.CREDENTIALS_FILE_PROFILE),
                    mapOf(
                        Constants.AWS.SHARED_CREDENTIALS_FILE_ENV to creds.path,
                        Constants.AWS.CONFIG_FILE_ENV to Constants.AWS.NO_CONFIG_FILE,
                    ),
                )
        }
    }

    private companion object {
        // ssh substitutes the target port for this token in a ProxyCommand
        const val SSH_CONFIG_PORT_TOKEN = "%p"
    }
}
