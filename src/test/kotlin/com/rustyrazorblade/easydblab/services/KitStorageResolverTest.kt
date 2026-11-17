package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The kit StorageClass of a cluster easy-db-lab did not build is the one the operator named, else
 * the cluster's default; either must reclaim with `Retain`, and a refusal is reported as an event
 * before the command fails.
 */
class KitStorageResolverTest {
    private val k8sService: K8sService = mock()
    private val events = mutableListOf<Event>()
    private val eventBus = EventBus()
    private val resolver = KitStorageResolver(k8sService, eventBus)

    @BeforeEach
    fun listen() {
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
    }

    private fun policy(
        storageClass: String,
        reclaimPolicy: String?,
    ) {
        whenever(k8sService.storageClassReclaimPolicy(storageClass)).thenReturn(Result.success(reclaimPolicy))
    }

    @Test
    fun `a named class that retains is the kit class, and the default is not consulted`() {
        policy("retain-x", "Retain")

        assertThat(resolver.resolve("retain-x")).isEqualTo("retain-x")
        verify(k8sService, never()).defaultStorageClass()
        assertThat(events).isEmpty()
    }

    @Test
    fun `with no class named, the cluster's default class is used when it retains`() {
        whenever(k8sService.defaultStorageClass()).thenReturn(Result.success("retained-default"))
        policy("retained-default", "Retain")

        assertThat(resolver.resolve(null)).isEqualTo("retained-default")
    }

    @Test
    fun `a default class that deletes is refused with an event naming it and its policy`() {
        whenever(k8sService.defaultStorageClass()).thenReturn(Result.success("gp2"))
        policy("gp2", "Delete")

        assertThatThrownBy { resolver.resolve(null) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("StorageClass gp2 reclaims with Delete")
            .hasMessageContaining("init --kit-storage-class")
        val refused = events.single() as Event.Setup.KitStorageClassRefused
        assertThat(refused.storageClass).isEqualTo("gp2")
        assertThat(refused.reclaimPolicy).isEqualTo("Delete")
        assertThat(refused.isError()).isTrue()
    }

    @Test
    fun `a named class that does not exist is refused with no policy`() {
        policy("gone", null)

        assertThatThrownBy { resolver.resolve("gone") }.hasMessageContaining("StorageClass gone does not exist")
        val refused = events.single() as Event.Setup.KitStorageClassRefused
        assertThat(refused.storageClass).isEqualTo("gone")
        assertThat(refused.reclaimPolicy).isNull()
    }

    @Test
    fun `with no class named and no default class, it fails naming the flag`() {
        whenever(k8sService.defaultStorageClass()).thenReturn(Result.success(null))

        assertThatThrownBy { resolver.resolve(null) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no default StorageClass")
            .hasMessageContaining("--kit-storage-class")
        assertThat(events).singleElement().isInstanceOf(Event.Setup.NoDefaultKitStorageClass::class.java)
    }
}
