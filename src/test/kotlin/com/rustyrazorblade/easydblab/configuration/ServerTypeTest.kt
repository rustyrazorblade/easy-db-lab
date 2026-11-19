package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [ServerType.ordinalOf] is what a re-run of `up` reads to find the ordinals still missing, so a
 * misread alias would launch a duplicate or skip a node.
 */
class ServerTypeTest {
    @Test
    fun `the ordinal is the number after the server type's prefix`() {
        assertThat(ServerType.Cassandra.ordinalOf("db0")).isEqualTo(0)
        assertThat(ServerType.Cassandra.ordinalOf("db12")).isEqualTo(12)
        assertThat(ServerType.Control.ordinalOf("control0")).isEqualTo(0)
        assertThat(ServerType.Stress.ordinalOf("app3")).isEqualTo(3)
    }

    @Test
    fun `an alias of another server type has no ordinal`() {
        assertThat(ServerType.Cassandra.ordinalOf("app3")).isNull()
        assertThat(ServerType.Stress.ordinalOf("db3")).isNull()
        assertThat(ServerType.Cassandra.ordinalOf("12")).isNull()
    }

    @Test
    fun `an alias whose suffix is not a plain number has no ordinal`() {
        assertThat(ServerType.Cassandra.ordinalOf("dbx")).isNull()
        assertThat(ServerType.Cassandra.ordinalOf("db")).isNull()
        assertThat(ServerType.Cassandra.ordinalOf("db-1")).isNull()
        assertThat(ServerType.Cassandra.ordinalOf("db+1")).isNull()
        assertThat(ServerType.Cassandra.ordinalOf("db1a")).isNull()
    }
}
