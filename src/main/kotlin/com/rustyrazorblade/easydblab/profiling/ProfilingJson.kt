package com.rustyrazorblade.easydblab.profiling

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

private val log = KotlinLogging.logger {}

/**
 * JSON codec shared by the two profiling documents exchanged with each node — the desired state the
 * CLI writes and the effective state the reconciler rewrites every pass.
 *
 * `ignoreUnknownKeys` so a node running an older reconciler than the CLI (or the reverse) degrades
 * to missing fields rather than an exception; `encodeDefaults` so the document the node reads is
 * always complete rather than relying on the reader's defaults matching the writer's.
 */
internal val profilingJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

/**
 * Decodes a profiling document, treating empty, truncated, and malformed input alike.
 *
 * Both documents are written by processes that can be interrupted — the CLI mid-upload, the
 * reconciler mid-rewrite — and read at arbitrary instants, so an unreadable document is an expected
 * state to report, not an exception to propagate.
 *
 * The failure is logged rather than swallowed outright: callers degrade to "no state", and without
 * a line naming what failed to parse, a node holding a corrupt document is indistinguishable from
 * one that was never configured — including to the person trying to work out why.
 *
 * @param source what was being read, for the diagnostic — e.g. `db0:/etc/easy-db-lab/profiling.json`.
 * @return the decoded document, or null if it could not be read.
 */
internal fun <T> decodeProfilingDocumentOrNull(
    document: String,
    serializer: KSerializer<T>,
    source: String = "",
): T? =
    if (document.isBlank()) {
        null
    } else {
        runCatching { profilingJson.decodeFromString(serializer, document) }
            .onFailure { failure ->
                log.warn(failure) {
                    "Unreadable profiling document${if (source.isEmpty()) "" else " at $source"} " +
                        "(${document.length} chars); treating it as absent"
                }
            }.getOrNull()
    }
