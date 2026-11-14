package com.rustyrazorblade.easydblab.configuration.store

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

    /** A blank bucket would render configs that write nowhere; `up` is what sets the bucket. */
    @Test
    fun `a cluster with no account bucket yet fails and says to run up`() {
        assertThatThrownBy { StoreBackend.from(state.copy(s3Bucket = null), defaultRegion = "eu-west-1") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("lab")
            .hasMessageContaining("easy-db-lab up")
    }

    /** The prefix does not depend on the bucket, so it can be read before `up` sets one. */
    @Test
    fun `the prefix of a cluster with no account bucket yet is no prefix`() {
        assertThat(StoreBackend.prefixOf(state.copy(s3Bucket = null))).isEqualTo(StoreBackend.NO_PREFIX)
    }
}
