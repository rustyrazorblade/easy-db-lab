package com.rustyrazorblade.easydblab.events

import org.testcontainers.containers.GenericContainer
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPubSub
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What the Redis integration tests share: the image, a URL for a container, a URL that no Redis
 * answers, and a subscriber that collects every message on one channel.
 */
internal object RedisTestSupport {
    const val IMAGE = "redis:7-alpine"
    const val PORT = 6379
    private const val SUBSCRIBE_WAIT_MS = 200L
    private const val JOIN_WAIT_MS = 2000L

    fun newContainer(): GenericContainer<*> = GenericContainer(IMAGE).withExposedPorts(PORT)

    fun url(
        container: GenericContainer<*>,
        channel: String,
    ): String = "redis://${container.host}:${container.getMappedPort(PORT)}/$channel"

    /** A URL on a loopback port that was free a moment ago, so nothing answers it. */
    fun deadUrl(channel: String): String {
        val port = ServerSocket(0).use { it.localPort }
        return "redis://127.0.0.1:$port/$channel"
    }

    /**
     * Subscribes to [channel] on [container], runs [action], and returns the messages that arrive
     * until [expected] have come or five seconds pass.
     */
    fun collect(
        container: GenericContainer<*>,
        channel: String,
        expected: Int,
        action: () -> Unit,
    ): List<String> {
        val received = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(expected)
        val pubSub =
            object : JedisPubSub() {
                override fun onMessage(
                    channel: String,
                    message: String,
                ) {
                    received.add(message)
                    latch.countDown()
                }
            }
        val subscriber = Jedis(container.host, container.getMappedPort(PORT))
        val thread = Thread { subscriber.subscribe(pubSub, channel) }
        thread.start()
        try {
            Thread.sleep(SUBSCRIBE_WAIT_MS)
            action()
            latch.await(5, TimeUnit.SECONDS)
        } finally {
            pubSub.unsubscribe()
            thread.join(JOIN_WAIT_MS)
            subscriber.close()
        }
        return received.toList()
    }
}
