package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContractSource
import com.rustyrazorblade.easydblab.configuration.environment.ToolingForm
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module
import java.nio.file.Path

private val clusterToolingModuleLog = KotlinLogging.logger {}

/**
 * The [ClusterTooling] binding on its own, so a test that does not load [servicesModule] can still
 * resolve the production choice of where helm and kubectl run.
 *
 * Where they run follows the recorded environment: over SSH on the control node, or locally through
 * the workspace bin/ wrappers. A factory, resolved from state.json each time, so an `init` inside the
 * REPL or the server switches it for the next command. The SSH form's directory holds its local
 * staging copy of stdin; it is injected because the JVM caches java.io.tmpdir at startup.
 */
val clusterToolingModule =
    module {
        factoryOf(::KitProcessEnvironment)
        factory<ClusterTooling> {
            val form = get<ClusterContractSource>().current().toolingForm
            clusterToolingModuleLog.debug { "Cluster tooling form: $form" }
            when (form) {
                ToolingForm.Ssh -> SshClusterTooling(get(), get(), Path.of(System.getProperty("java.io.tmpdir")))
                ToolingForm.Local -> LocalClusterTooling(get<Context>().workingDirectory, get())
            }
        }
    }
