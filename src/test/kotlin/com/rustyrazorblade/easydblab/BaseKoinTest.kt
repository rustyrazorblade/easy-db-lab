package com.rustyrazorblade.easydblab

import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.koin.core.module.Module
import org.koin.test.KoinTest
import org.koin.test.get
import org.koin.test.junit5.KoinTestExtension
import java.io.File

@ResourceLock("koin")
abstract class BaseKoinTest : KoinTest {
    @TempDir
    lateinit var tempDir: File

    protected val context: Context
        get() = get()

    protected open fun coreTestModules(): List<Module> = TestModules.coreTestModules(tempDir)

    protected open fun additionalTestModules(): List<Module> = emptyList()

    @JvmField
    @RegisterExtension
    val koinTestExtension =
        KoinTestExtension.create {
            modules(coreTestModules() + additionalTestModules())
        }
}
