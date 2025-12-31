package com.rustyrazorblade.easydblab.observability

import com.rustyrazorblade.easydblab.services.DefaultVictoriaLogsService
import com.rustyrazorblade.easydblab.services.VictoriaLogsService
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Koin module for observability services.
 *
 * This module provides services for logging, metrics, and monitoring:
 * - VictoriaLogsService: Query logs from Victoria Logs
 *
 * Future additions may include:
 * - Metrics services (Victoria Metrics)
 * - Tracing services
 * - Alerting services
 */
val observabilityModule =
    module {
        factoryOf(::DefaultVictoriaLogsService) bind VictoriaLogsService::class
    }
