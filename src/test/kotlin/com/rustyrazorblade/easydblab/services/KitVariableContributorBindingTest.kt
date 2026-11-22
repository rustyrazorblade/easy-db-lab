package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.cassandraversions.CassandraTarballResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.mockito.kotlin.mock

/**
 * The contributors the production [servicesModule] registers are exactly the names a `kit.yaml`
 * may list ([Constants.Kit.Contributors.ALL]). A name in the list with no binding parses and then
 * fails at `start`; a binding missing from the list cannot be named by any kit.
 */
class KitVariableContributorBindingTest {
    @Test
    fun `the registered contributors are exactly the names kit yaml accepts`() {
        val app =
            koinApplication {
                modules(
                    servicesModule,
                    // The leaves: what the contributors resolve and fetch, not what is under test.
                    module {
                        single<K8sService> { mock() }
                        single<CassandraTarballResolver> { mock() }
                        single { EventBus() }
                    },
                )
            }
        try {
            val names = app.koin.getAll<KitVariableContributor>().map { it.name }

            assertThat(names).containsExactlyInAnyOrderElementsOf(Constants.Kit.Contributors.ALL)
        } finally {
            app.close()
        }
    }
}
