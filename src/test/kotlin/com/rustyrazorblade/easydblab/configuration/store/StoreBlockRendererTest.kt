package com.rustyrazorblade.easydblab.configuration.store

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StoreBlockRendererTest {
    private val fragment =
        """
        |backend: stub
        |
        |# a comment
        |stub:
        |  dir: /x
        |
        """.trimMargin()

    private val renderer = StoreBlockRenderer(StoreBlockSource { fragment })

    @Test
    fun `the fragment replaces the placeholder line at the placeholder's indentation`() {
        val config =
            """
            |top:
            |  ${StoreBlockRenderer.PLACEHOLDER}
            |after: 1
            |
            """.trimMargin()

        assertThat(renderer.render(config, javaClass)).isEqualTo(
            """
            |top:
            |  backend: stub
            |
            |  # a comment
            |  stub:
            |    dir: /x
            |after: 1
            |
            """.trimMargin(),
        )
    }

    @Test
    fun `a config without the placeholder is refused`() {
        assertThatThrownBy { renderer.render("a: 1\n", javaClass) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(StoreBlockRenderer.PLACEHOLDER)
    }

    @Test
    fun `a config with the placeholder twice is refused`() {
        val config = "a:\n  ${StoreBlockRenderer.PLACEHOLDER}\nb:\n  ${StoreBlockRenderer.PLACEHOLDER}\n"

        assertThatThrownBy { renderer.render(config, javaClass) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the S3 source reads the s3 fragment beside the config`() {
        val text = StoreBlockSource.of(S3Backend(bucket = "b", region = "r")).fragment(StoreBlockRendererTest::class.java)

        assertThat(text).isEqualTo("stub-s3-fragment: true\n")
    }
}
