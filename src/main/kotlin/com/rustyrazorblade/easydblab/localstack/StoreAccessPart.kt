package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.TemplateService

/**
 * The live stack's read-only access to the account bucket.
 *
 * - `store-signer`: the cluster's `aws-sigv4-proxy`, signing with the operator's profile from `~/.aws`,
 *   mounted read-only (the container runs as root and would otherwise rewrite the SSO cache). Its
 *   compose healthcheck lists the bucket every minute, so `docker compose ps` shows it unhealthy once
 *   the credentials can no longer read it.
 * - `store-gate`: an nginx that passes only GET and HEAD to the signer. Every component's S3
 *   endpoint is the gate, so nothing in the stack can write, rewrite or delete an object.
 * - `store-check`: a one-shot read of the bucket that every backend waits for, so expired
 *   credentials stop the stack from starting instead of showing an empty store.
 *
 * The signer signs any method, so no other service may reach it: it shares the internal
 * `store-access` network only with the gate and the store check, and reaches S3 over `store-egress`,
 * which no other service joins. The gate is also on the project's `default` network, where the
 * backends, Grafana and the documents view reach it.
 */
object StoreAccessPart {
    private const val GATE_CONFIG = "store-gate.conf"
    private const val CHECK_SCRIPT = "store-check.sh"
    private const val CHECK_DIR = "/store-check"
    private const val HEALTHCHECK_TOOLS_MOUNT = "/healthcheck"
    private const val HEALTHCHECK_TIMEOUT = "10s"
    private const val HEALTHCHECK_RETRIES = 2
    private const val HEALTHCHECK_START_PERIOD = "10s"
    private const val ACCESS = Constants.LocalStack.Networks.STORE_ACCESS
    private const val EGRESS = Constants.LocalStack.Networks.STORE_EGRESS

    /** The part for the live store in [live]'s bucket and region, read with its profile. */
    fun live(live: LocalStackSpec.Mode.Live): ComposePart =
        ComposePart(
            services =
                mapOf(
                    Constants.LocalStack.Services.STORE_SIGNER to signer(live),
                    Constants.LocalStack.Services.STORE_GATE to gate(),
                    Constants.LocalStack.Services.STORE_CHECK to check(live),
                ),
            files =
                mapOf(
                    LocalStackComposer.configPath(Constants.LocalStack.Services.STORE_GATE, LocalStackComposer.NGINX_CONFIG_FILE) to
                        gateConfig(),
                    LocalStackComposer.configPath(Constants.LocalStack.Services.STORE_CHECK, CHECK_SCRIPT) to
                        LocalStackComposer.resource(CHECK_SCRIPT),
                ),
            networks = mapOf(ACCESS to ComposeNetwork(internal = true), EGRESS to ComposeNetwork()),
        )

    private fun gateConfig(): String =
        TemplateService
            .Template(LocalStackComposer.resource(GATE_CONFIG), emptyMap())
            .substitute(
                mapOf(
                    "STORE_PORT" to Constants.LocalStack.STORE_PORT.toString(),
                    "STORE_SIGNER" to LocalStackComposer.STORE_SIGNER_ENDPOINT,
                ),
            )

    private fun signer(live: LocalStackSpec.Mode.Live): ComposeService =
        ComposeService(
            image = LocalImages.SIGNER,
            command =
                listOf(
                    "--name",
                    "s3",
                    "--region",
                    live.region,
                    "--host",
                    "s3.${live.region}.amazonaws.com",
                    "--port",
                    ":${Constants.LocalStack.STORE_PORT}",
                ),
            // HOME: aws-sdk-go v1 finds the mounted ~/.aws only through it; see SIGNER_HOME.
            environment = mapOf("HOME" to Constants.LocalStack.SIGNER_HOME, "AWS_PROFILE" to live.awsProfile),
            volumes =
                listOf(
                    ComposeMount.bind("~/.aws", "${Constants.LocalStack.SIGNER_HOME}/.aws", readOnly = true),
                    ComposeMount.image(LocalImages.HEALTHCHECK_TOOLS, HEALTHCHECK_TOOLS_MOUNT),
                ),
            healthcheck =
                ComposeHealthcheck(
                    test =
                        listOf(
                            "CMD",
                            "$HEALTHCHECK_TOOLS_MOUNT/bin/wget",
                            "-q",
                            "--spider",
                            "http://${Constants.LocalStack.LOOPBACK}:${Constants.LocalStack.STORE_PORT}/${live.bucket}" +
                                "?list-type=2&prefix=mimir%2F&max-keys=1",
                        ),
                    interval = Constants.LocalStack.SIGNER_HEALTHCHECK_INTERVAL,
                    timeout = HEALTHCHECK_TIMEOUT,
                    retries = HEALTHCHECK_RETRIES,
                    startPeriod = HEALTHCHECK_START_PERIOD,
                ),
            restart = "unless-stopped",
            networks = listOf(ACCESS, EGRESS),
        )

    private fun gate(): ComposeService =
        ComposeService(
            image = LocalImages.GATE,
            command = LocalStackComposer.nginxCommand,
            volumes = listOf(LocalStackComposer.configMount(Constants.LocalStack.Services.STORE_GATE, LocalStackComposer.NGINX_CONFIG_DIR)),
            dependsOn = mapOf(Constants.LocalStack.Services.STORE_SIGNER to ComposeDependency.STARTED),
            restart = "unless-stopped",
            networks = listOf(Constants.LocalStack.Networks.DEFAULT, ACCESS),
        )

    private fun check(live: LocalStackSpec.Mode.Live): ComposeService =
        ComposeService(
            image = LocalImages.GATE,
            entrypoint = listOf("/bin/sh", "$CHECK_DIR/$CHECK_SCRIPT"),
            environment =
                mapOf(
                    "BUCKET" to live.bucket,
                    "PROFILE" to live.awsProfile,
                    "SIGNER" to LocalStackComposer.STORE_SIGNER_ENDPOINT,
                ),
            volumes = listOf(LocalStackComposer.configMount(Constants.LocalStack.Services.STORE_CHECK, CHECK_DIR)),
            dependsOn = mapOf(Constants.LocalStack.Services.STORE_SIGNER to ComposeDependency.STARTED),
            restart = "no",
            networks = listOf(ACCESS),
        )
}
