package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class ContextFactoryTest : BaseKoinTest() {
    @Test
    fun `getContext caches contexts by key`() {
        val factory = ContextFactory(tempDir)

        val context1 = factory.getContext("test1")
        val context2 = factory.getContext("test1")

        assertThat(context1).isSameAs(context2)
    }

    @Test
    fun `different keys return different contexts`() {
        val factory = ContextFactory(tempDir)

        val context1 = factory.getContext("test1")
        val context2 = factory.getContext("test2")

        assertThat(context1).isNotSameAs(context2)
    }

    @Test
    fun `getDefault returns context with default key`() {
        val factory = ContextFactory(tempDir)

        val defaultContext = factory.getDefault()
        val explicitDefault = factory.getContext("default")

        assertThat(defaultContext).isSameAs(explicitDefault)
    }

    @Test
    fun `clearContext removes specific context from cache`() {
        val factory = ContextFactory(tempDir)

        val context1 = factory.getContext("test")
        factory.clearContext("test")
        val context2 = factory.getContext("test")

        assertThat(context1).isNotSameAs(context2)
    }

    @Test
    fun `clearAll removes all contexts from cache`() {
        val factory = ContextFactory(tempDir)

        val context1 = factory.getContext("test1")
        val context2 = factory.getContext("test2")

        factory.clearAll()

        val context3 = factory.getContext("test1")
        val context4 = factory.getContext("test2")

        assertThat(context1).isNotSameAs(context3)
        assertThat(context2).isNotSameAs(context4)
    }

    @Test
    fun `default context uses base directory directly`() {
        val factory = ContextFactory(tempDir)

        val defaultContext = factory.getDefault()

        assertThat(defaultContext.easyDbLabUserDirectory).isEqualTo(tempDir)
    }

    @Test
    fun `non-default context uses subdirectory`() {
        val factory = ContextFactory(tempDir)

        val testContext = factory.getContext("test-env")
        val expectedDir = File(tempDir, "test-env")

        assertThat(testContext.easyDbLabUserDirectory).isEqualTo(expectedDir)
    }
}
