package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What a refusal message carries of a backend's answer: the whole body when it is short, else its
 * first [Constants.Export.Query.ANSWER_EXCERPT_CHARS] characters and how many were left out, so a
 * backend that answers with a page of HTML or a large JSON error does not fill the screen and the
 * `Export.Failed` event.
 */
class AnswerExcerptTest {
    private val limit = Constants.Export.Query.ANSWER_EXCERPT_CHARS

    @Test
    fun `a body up to the limit is kept whole`() {
        val body = "x".repeat(limit)

        assertThat(answerExcerpt(body)).isEqualTo(body)
    }

    @Test
    fun `a longer body is cut at the limit and says how many characters were left out`() {
        val body = "a".repeat(limit) + "b".repeat(42)

        assertThat(answerExcerpt(body))
            .startsWith("a".repeat(limit))
            .doesNotContain("b")
            .endsWith("(42 more characters)")
    }
}
