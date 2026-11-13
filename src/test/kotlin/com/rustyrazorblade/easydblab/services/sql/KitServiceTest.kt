package com.rustyrazorblade.easydblab.services.sql

import com.rustyrazorblade.easydblab.services.KitRuntime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KitServiceTest {
    @Test
    fun `a Helm kit's Service is named for its release, in its namespace`() {
        val runtime = KitRuntime(type = KitRuntime.RuntimeType.HELM, release = "presto", namespace = "analytics")

        assertThat(KitService.of("presto", runtime)).isEqualTo(KitService("analytics", "presto"))
    }

    @Test
    fun `any other kit publishes its endpoints on its NodePort Service`() {
        val runtime = KitRuntime(type = KitRuntime.RuntimeType.PODS, selector = "easydblab/kit=postgres-vector")

        assertThat(KitService.of("postgres-vector", runtime)).isEqualTo(KitService("default", "postgres-vector-nodeport"))
    }

    @Test
    fun `a kit with no runtime is in the default namespace`() {
        assertThat(KitService.of("memcached", null)).isEqualTo(KitService("default", "memcached-nodeport"))
    }
}
