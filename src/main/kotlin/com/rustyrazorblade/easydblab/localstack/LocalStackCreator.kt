package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import java.io.File

/**
 * `observability local create <dir>`: writes a live local stack that reads the shared store with the
 * operator's own AWS profile.
 *
 * In order, stopping at the first failure:
 * 1. the home tenant must match the tenant rule;
 * 2. the directory must be missing, empty, or a stack this command wrote ([LocalStackWriter.refuseForeign]);
 * 3. the profile must name an AWS profile and an account bucket;
 * 4. the profile must be able to read the bucket ([LocalStackStore.check]); otherwise
 *    [Event.LocalStack.StoreUnreadable] and nothing is written;
 * 5. the tenants are listed again, the stack is composed and its owned files written, and the
 *    packaged dashboards installed in `dashboards/current`;
 * 6. on a re-run, a Grafana answering on the stack's port reloads its datasources and dashboards,
 *    so a new tenant shows with no restart. A first run never reloads: whatever answers on the
 *    port is not this stack's.
 *
 * It never opens `data/`, `cache/`, `grafana-data/` or `compose.override.yml`, and never writes to
 * the bucket.
 *
 * @property user the easy-db-lab profile: the AWS profile and the account bucket.
 * @property store the reads of the account bucket.
 * @property writer writes the owned files and the manifest.
 * @property grafana the stack's Grafana, for the datasource reload.
 * @property dashboards installs the dashboard tree.
 * @property eventBus reports what was written.
 */
class LocalStackCreator(
    private val user: User,
    private val store: LocalStackStore,
    private val writer: LocalStackWriter,
    private val grafana: LocalGrafana,
    private val dashboards: LocalStackDashboards,
    private val eventBus: EventBus,
) {
    private companion object {
        val TENANT = Regex(Constants.Observability.TENANT_PATTERN)
    }

    /** Writes, or rewrites, the stack in [directory] with [homeTenant] as the stable datasources' tenant. */
    fun create(
        directory: File,
        homeTenant: String,
    ) {
        require(TENANT.matches(homeTenant) && homeTenant != Constants.Observability.RESERVED_TENANT) {
            "--tenant $homeTenant is not a tenant name: it must match ${Constants.Observability.TENANT_PATTERN} " +
                "and not be '${Constants.Observability.RESERVED_TENANT}'"
        }
        writer.refuseForeign(directory)
        val profile =
            user.awsProfile.ifBlank {
                error(
                    "The easy-db-lab profile names no AWS profile; the local stack reads ~/.aws with one",
                )
            }
        val bucket = user.s3Bucket.ifBlank { error("No account bucket in the profile. Run 'easy-db-lab up' once to create it.") }
        val region =
            when (val access = store.check(bucket)) {
                is StoreReadResult.Readable -> access.region
                is StoreReadResult.Unreadable -> {
                    eventBus.emit(Event.LocalStack.StoreUnreadable(profile, access.awsErrorCode))
                    throw CommandFailedException("The account bucket is not readable with profile $profile")
                }
            }
        val spec =
            LocalStackSpec(
                mode = LocalStackSpec.Mode.Live(bucket = bucket, region = region, awsProfile = profile, home = homeTenant),
                tenants = store.tenants(bucket, homeTenant).all,
            )
        val result = writer.write(directory, spec, LocalStackComposer.compose(spec))
        val manifest = result.manifest
        val reload = result.rewrite && grafana.isRunning(manifest.grafanaPort)
        val installed = dashboards.install(directory, manifest, source = null, reload = reload)
        val grafanaUrl = grafanaUrl(manifest.grafanaPort)
        eventBus.emit(
            Event.LocalStack.Written(
                directory = directory.path,
                files = result.written.size,
                edited = result.edited,
                homeTenant = homeTenant,
                tenants = manifest.tenants,
                grafanaUrl = grafanaUrl,
            ),
        )
        eventBus.emit(installed)
        if (reload) {
            grafana.reloadDatasources(manifest.grafanaPort)
            eventBus.emit(Event.LocalStack.DatasourcesReloaded(grafanaUrl))
        }
    }
}

/** Where the browser reaches a local stack's Grafana published on [port]. */
internal fun grafanaUrl(port: Int): String = "http://${Constants.LocalStack.LOOPBACK}:$port"
