package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class EventContextTest {
    @AfterEach
    fun cleanup() {
        EventContext.clear()
    }

    @Test
    fun `empty stack returns null`() {
        assertThat(EventContext.current()).isNull()
    }

    @Test
    fun `single push and pop`() {
        EventContext.push("up")
        assertThat(EventContext.current()).isEqualTo("up")

        EventContext.pop()
        assertThat(EventContext.current()).isNull()
    }

    @Test
    fun `nested push shows innermost command`() {
        EventContext.push("init")
        assertThat(EventContext.current()).isEqualTo("init")

        EventContext.push("up")
        assertThat(EventContext.current()).isEqualTo("up")

        EventContext.pop()
        assertThat(EventContext.current()).isEqualTo("init")

        EventContext.pop()
        assertThat(EventContext.current()).isNull()
    }

    @Test
    fun `pop on empty stack is safe`() {
        EventContext.pop()
        assertThat(EventContext.current()).isNull()
    }

    @Test
    fun `thread isolation`() {
        val otherThreadResult = AtomicReference<String?>("unset")
        val latch = CountDownLatch(1)

        EventContext.push("main-command")

        val thread =
            Thread {
                otherThreadResult.set(EventContext.current())
                latch.countDown()
            }
        thread.start()
        latch.await()

        assertThat(EventContext.current()).isEqualTo("main-command")
        assertThat(otherThreadResult.get()).isNull()
    }
}
