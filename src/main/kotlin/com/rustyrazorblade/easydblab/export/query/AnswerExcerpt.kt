package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants

/**
 * What a refusal message carries of a backend's answer [body]: all of it up to
 * [Constants.Export.Query.ANSWER_EXCERPT_CHARS] characters, else that many and how many were left
 * out. A refusal's message becomes `Export.Failed`'s reason, and a backend may answer with a page of
 * HTML or a large JSON error, which would otherwise fill the screen and the event.
 */
internal fun answerExcerpt(body: String): String {
    val limit = Constants.Export.Query.ANSWER_EXCERPT_CHARS
    return if (body.length <= limit) body else "${body.take(limit)}... (${body.length - limit} more characters)"
}
