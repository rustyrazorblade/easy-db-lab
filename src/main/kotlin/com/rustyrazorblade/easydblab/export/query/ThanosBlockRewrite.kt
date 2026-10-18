package com.rustyrazorblade.easydblab.export.query

import com.charleskorn.kaml.Yaml
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.localstack.ComposeFile
import com.rustyrazorblade.easydblab.localstack.ComposeMount
import com.rustyrazorblade.easydblab.localstack.ComposeService
import com.rustyrazorblade.easydblab.localstack.LocalImages
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.Duration

/**
 * thanos's object store configuration: a filesystem rooted at the tenant directory, since thanos
 * expects blocks at the bucket root.
 *
 * @property type always `FILESYSTEM`.
 * @property config the root directory.
 */
@Serializable
data class ThanosObjstore(
    val type: String,
    val config: ThanosFilesystem,
)

/**
 * The root of a [ThanosObjstore].
 *
 * @property directory the directory, as the container sees it.
 */
@Serializable
data class ThanosFilesystem(
    val directory: String,
)

/**
 * One deletion of a `tools bucket rewrite`.
 *
 * @property matchers the series to delete, as a PromQL selector.
 */
@Serializable
data class ThanosDeletion(
    val matchers: String,
)

/**
 * [BlockRewrite] with the pinned thanos (spike S4): `tools bucket rewrite --prom-blocks --no-dry-run`
 * over a `FILESYSTEM` object store rooted at the staged tenant directory, deleting every series whose
 * `cluster` is not one of the selected, one `--id` per block. `--prom-blocks` is required (Mimir
 * blocks have empty external labels); `--delete-blocks` is never passed (the export removes the
 * sources itself, so no deletion mark is written) and neither is `--hash-func` (it would need an
 * xattr). thanos runs as a one-off compose run with `network_mode: none`, no environment and no
 * credentials, as the operator ([owner]), since the image's own user cannot write a directory it does
 * not own. Its project, one per tenant, sits in [projects]; it is removed once the run ends, and a
 * project compose cannot remove is reported ([ProjectRemoval]). A run that outlasts its timeout has its
 * container killed and removed, and fails; it fails saying the container may still be running unless
 * the project then lists no container.
 *
 * @property compose runs docker compose.
 * @property projects where each tenant's rewrite project is written.
 * @property project the export's project name, which each rewrite project's name starts with.
 * @property owner the operator's `uid:gid`.
 * @property eventBus reports a rewrite project that cannot be removed.
 */
class ThanosBlockRewrite(
    private val compose: ComposeCli,
    private val projects: File,
    private val project: String,
    private val owner: String,
    private val eventBus: EventBus,
) : BlockRewrite {
    private companion object {
        const val SERVICE = "thanos"
        const val STAGING = "/staging"
        const val CONFIG = "/etc/thanos"
        const val OBJSTORE = "objstore.yaml"
        const val DELETE = "delete.yaml"
        val TIMEOUT: Duration = Duration.ofHours(Constants.Export.Query.REWRITE_TIMEOUT_HOURS)
        val STOP_TIMEOUT: Duration = Duration.ofMinutes(Constants.Export.Query.THANOS_STOP_TIMEOUT_MINUTES)
    }

    override fun rewrite(
        tenantDir: File,
        tmpDir: File,
        blocks: List<String>,
        clusters: List<String>,
    ) {
        val staging = tmpDir.absoluteFile.parentFile
        require(tenantDir.absoluteFile.toPath().startsWith(staging.toPath())) { "$tenantDir is not under the staging directory $staging" }
        require(!tmpDir.absoluteFile.toPath().startsWith(tenantDir.absoluteFile.toPath())) {
            "thanos's scratch $tmpDir must be outside the tenant directory $tenantDir"
        }

        fun inContainer(file: File) = "$STAGING/${file.absoluteFile.relativeTo(staging).invariantSeparatorsPath}"
        val directory = File(projects, "rewrite-${tenantDir.name}").apply { mkdirs() }
        File(directory, "config").mkdirs()
        File(directory, "config/$OBJSTORE").writeText(
            Yaml.default.encodeToString(
                ThanosObjstore.serializer(),
                ThanosObjstore("FILESYSTEM", ThanosFilesystem(inContainer(tenantDir))),
            ),
        )
        File(directory, "config/$DELETE").writeText(
            Yaml.default.encodeToString(
                ListSerializer(ThanosDeletion.serializer()),
                listOf(ThanosDeletion("{cluster!~\"${PromQlValues.anyOf(clusters)}\"}")),
            ),
        )
        val service =
            ComposeService(
                image = LocalImages.THANOS,
                user = owner,
                networkMode = "none",
                command =
                    listOf(
                        "tools",
                        "bucket",
                        "rewrite",
                        "--objstore.config-file=$CONFIG/$OBJSTORE",
                        "--rewrite.to-delete-config-file=$CONFIG/$DELETE",
                        "--tmp.dir=${inContainer(tmpDir)}",
                        "--prom-blocks",
                        "--no-dry-run",
                    ) + blocks.map { "--id=$it" },
                volumes =
                    listOf(
                        ComposeMount.bind(staging.path, STAGING, readOnly = false),
                        ComposeMount.bind("./config", CONFIG, readOnly = true),
                    ),
                restart = "no",
            )
        File(directory, Constants.LocalStack.COMPOSE_FILE).writeText(ComposeFile(mapOf(SERVICE to service)).toYaml())
        val name = "$project-rewrite-${tenantDir.name}"
        val removal = ProjectRemoval(compose, eventBus)
        val result =
            try {
                compose.run(directory, name, listOf("run", "--rm", SERVICE), TIMEOUT)
            } catch (e: Exception) {
                removal.down(directory, name)?.let(e::addSuppressed)
                throw e
            }
        if (result.timedOut) throw stopAfterTimeout(directory, name, tenantDir.name, staging, result)
        removal.down(directory, name)
        check(result.exitCode == 0) {
            "thanos could not rewrite the blocks of ${tenantDir.name} (exit ${result.exitCode}):\n${result.output}"
        }
    }

    /**
     * At the timeout only the compose CLI was killed, and thanos's container may still be writing
     * into [staging]. Kills and removes the project's containers, then lists them again. The rewrite
     * fails either way; when the stop is not confirmed it says the container may still be running,
     * so nothing deletes the staged sources or writes an index meanwhile.
     */
    private fun stopAfterTimeout(
        directory: File,
        name: String,
        tenant: String,
        staging: File,
        result: ComposeResult,
    ): IllegalStateException {
        val kill = compose.run(directory, name, listOf("kill"), STOP_TIMEOUT)
        val removed = ProjectRemoval(compose, eventBus).down(directory, name)
        val listed = compose.run(directory, name, listOf("ps", "-a", "-q"), STOP_TIMEOUT)
        val stopped = kill.exitCode == 0 && removed == null && listed.exitCode == 0 && listed.output.isBlank()
        val head = "thanos did not finish rewriting the blocks of $tenant within $TIMEOUT"
        return if (stopped) {
            IllegalStateException(
                "$head; its container was stopped and removed, and the staged blocks were left as they were:\n${result.output}",
            )
        } else {
            IllegalStateException(
                "$head, and its container may still be running and writing into $staging. " +
                    "docker compose kill exited ${kill.exitCode}: ${kill.output.trim()}; " +
                    "down: ${removed?.message ?: "removed"}; still listed: ${listed.output.trim().ifEmpty { "none" }} " +
                    "(ps exit ${listed.exitCode}). Stop it in $directory with: docker compose -p $name kill, " +
                    "then docker compose -p $name down --remove-orphans",
            )
        }
    }
}
