package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.export.ExportStore
import com.rustyrazorblade.easydblab.localstack.LocalStackSpec

/**
 * The account bucket as a query export reads it: the store, and where the reader stack's signer finds
 * it, with which AWS profile.
 *
 * @property store the bucket, list and read only.
 * @property bucket the bucket.
 * @property region its region, which the signer signs for.
 * @property profile the AWS profile the signer reads `~/.aws` with.
 */
data class QueryStoreAccess(
    val store: ExportStore,
    val bucket: String,
    val region: String,
    val profile: String,
) {
    /** The reader's live store, with [home] as the tenant its configuration files expand. */
    fun live(home: String): LocalStackSpec.Mode.Live =
        LocalStackSpec.Mode.Live(bucket = bucket, region = region, awsProfile = profile, home = home)
}

/** Opens the account bucket for a query export, after checking it is readable. */
fun interface QueryStoreFactory {
    /** The bucket; the caller closes the store. */
    fun open(): QueryStoreAccess
}

/** Starts a query export's temporary stacks and its metrics rewrite, in its work directory. */
interface QueryRuntime {
    /** The reader over the live store [live] names, started and ready. */
    fun reader(
        work: QueryWorkDirectory,
        live: LocalStackSpec.Mode.Live,
    ): ExportStack

    /** The writer, its home tenant [home], started and ready. */
    fun writer(
        work: QueryWorkDirectory,
        home: String,
    ): ExportStack

    /** The metrics rewrite. */
    fun rewrite(work: QueryWorkDirectory): BlockRewrite
}

/**
 * [QueryRuntime] over docker compose: the reader ([ExportReaderStack]) and the writer
 * ([ExportWriterStack]) as `<project>-reader` and `<project>-writer` in the work directory, and the
 * rewrite with thanos ([ThanosBlockRewrite]). The writers and thanos run as the owner of the work
 * directory, the operator.
 *
 * @property runtime how the projects run and are reached.
 */
class DockerQueryRuntime(
    private val runtime: StackRuntime,
) : QueryRuntime {
    override fun reader(
        work: QueryWorkDirectory,
        live: LocalStackSpec.Mode.Live,
    ): ExportStack =
        DockerExportStack.start(
            work.reader,
            StackDefinition(
                project = "${work.project}-reader",
                files = ExportReaderStack.compose(live),
                published = ExportReaderStack.PUBLISHED.mapValues { listOf(it.value) },
            ),
            runtime,
        )

    override fun writer(
        work: QueryWorkDirectory,
        home: String,
    ): ExportStack =
        DockerExportStack.start(
            work.writer,
            StackDefinition(
                project = "${work.project}-writer",
                files = ExportWriterStack.compose(home, work.owner()),
                published =
                    mapOf(
                        Constants.LocalStack.Services.LOKI to listOf(Constants.K8s.LOKI_HTTP_PORT),
                        Constants.LocalStack.Services.TEMPO to listOf(Constants.K8s.TEMPO_PORT, Constants.K8s.TEMPO_OTLP_HTTP_PORT),
                    ),
            ),
            runtime,
        )

    override fun rewrite(work: QueryWorkDirectory): BlockRewrite =
        ThanosBlockRewrite(runtime.compose, work.projects, work.project, work.owner(), runtime.eventBus, runtime.shutdownHooks)
}
