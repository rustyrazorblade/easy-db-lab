package com.rustyrazorblade.easydblab.configuration.store

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StoreBackendTest {
    private val state =
        ClusterState(
            name = "lab",
            versions = mutableMapOf(),
            s3Bucket = "easy-db-lab-x",
            initConfig = InitConfig(region = "us-west-2"),
        )

    @Test
    fun `a cluster resolves to the S3 backend of its account bucket and region, with no prefix`() {
        assertThat(StoreBackend.from(state, defaultRegion = "eu-west-1"))
            .isEqualTo(S3Backend(bucket = "easy-db-lab-x", prefix = "", region = "us-west-2", endpoint = "s3.us-west-2.amazonaws.com"))
    }

    @Test
    fun `a cluster that records no region uses the default region`() {
        val backend = StoreBackend.from(state.copy(initConfig = null), defaultRegion = "eu-west-1") as S3Backend

        assertThat(backend.region).isEqualTo("eu-west-1")
        assertThat(backend.endpoint).isEqualTo("s3.eu-west-1.amazonaws.com")
    }
}
