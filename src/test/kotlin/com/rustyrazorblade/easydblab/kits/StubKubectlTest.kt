package com.rustyrazorblade.easydblab.kits

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Checks the stub `kubectl` the kit tests run shell steps against: canned replies are served in
 * order and the last repeats, and an unmatched call passes its standard input through and keeps it.
 */
class StubKubectlTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `canned replies are served in order and the last one repeats`() {
        val stub = StubKubectl(dir)
        stub.reply("get pods", "first", "second")

        val exit = stub.run("for i in 1 2 3; do kubectl get pods -o json; echo; done", emptyMap())

        assertThat(exit).isZero()
        assertThat(stub.output().lines().filter { it.isNotBlank() }).containsExactly("first", "second", "second")
    }

    @Test
    fun `a canned reply exits with its exit code`() {
        val stub = StubKubectl(dir)
        stub.reply("logs", "boom", exitCode = 3)

        assertThat(stub.run("kubectl logs ferrosa-0", emptyMap())).isEqualTo(3)
    }

    @Test
    fun `an unmatched call passes stdin through and records it`() {
        val stub = StubKubectl(dir)
        stub.reply("get pods", "{}")

        stub.run("printf 'kind: Service\\n' | kubectl apply -f -", emptyMap())

        assertThat(stub.output()).contains("kind: Service")
        assertThat(stub.stdinOf("apply")).containsExactly("kind: Service\n")
        assertThat(stub.invocations()).containsExactly("apply -f -")
    }
}
