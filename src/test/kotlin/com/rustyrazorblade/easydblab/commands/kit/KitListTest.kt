package com.rustyrazorblade.easydblab.commands.kit

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Runs `kit list` against the real template resolver, which reads the built-in kits from the
 * classpath, and checks what it prints.
 */
class KitListTest : BaseKoinTest() {
    private val stdout = ByteArrayOutputStream()
    private val originalOut = System.out

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { KitSourcesProvider(get()) }
                single { InstallTemplateResolver(get(), get()) }
            },
        )

    @BeforeEach
    fun captureStdout() {
        System.setOut(PrintStream(stdout))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
    }

    /** ferrosa-kit: "kit list shows ferrosa". */
    @Test
    fun `kit list shows the built-in ferrosa kit with its version and description`() {
        KitList().execute()

        val ferrosa = stdout.toString().lines().single { it.trim().startsWith("ferrosa ") }
        assertThat(ferrosa).contains("1.0.0", "FerrosaDB ring")
    }
}
