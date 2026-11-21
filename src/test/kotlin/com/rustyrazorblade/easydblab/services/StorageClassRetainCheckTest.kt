package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import com.rustyrazorblade.easydblab.kubernetes.KubernetesDenials
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * A claim on a StorageClass that does not retain its volumes would delete the owner's data with the
 * claim, so the check refuses any class whose reclaim policy is not `Retain`, naming the class and
 * the fix for what it would hold.
 */
class StorageClassRetainCheckTest {
    private val k8sService: K8sService = mock()
    private val check = StorageClassRetainCheck(k8sService)

    private fun policy(
        storageClass: String,
        reclaimPolicy: String?,
    ) {
        whenever(k8sService.storageClassReclaimPolicy(storageClass)).thenReturn(Result.success(reclaimPolicy))
    }

    @Test
    fun `a class that reclaims with Retain is accepted`() {
        policy("retain-x", "Retain")

        assertThatCode { check.require("retain-x", RetainedData.KitData) }.doesNotThrowAnyException()
    }

    @Test
    fun `a kit class that deletes is refused naming the class, its policy and the init flag`() {
        policy("gp2", "Delete")

        assertThatThrownBy { check.require("gp2", RetainedData.KitData) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage(
                "StorageClass gp2 reclaims with Delete, which would delete kit data when a kit's claim is deleted. " +
                    "Pass init --kit-storage-class naming a StorageClass with reclaimPolicy: Retain, " +
                    "or create one (a copy of gp2 with reclaimPolicy: Retain).",
            )
    }

    @Test
    fun `a missing kit class is reported as missing with the same fix`() {
        policy("gone", null)

        assertThatThrownBy { check.require("gone", RetainedData.KitData) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("StorageClass gone does not exist")
            .hasMessageContaining("init --kit-storage-class")
            .hasMessageContaining("reclaimPolicy: Retain")
    }

    @Test
    fun `a backend class that deletes is refused naming the backends' state`() {
        policy("gp3", "Delete")

        assertThatThrownBy { check.require("gp3", RetainedData.BackendState) }
            .hasMessageContaining("StorageClass gp3 for the backends' state reclaims with Delete")
            .hasMessageContaining("the backends' state")
            .hasMessageContaining("Retain")
    }

    @Test
    fun `a class that cannot be read fails naming it`() {
        whenever(k8sService.storageClassReclaimPolicy("x")).thenReturn(Result.failure(RuntimeException("forbidden")))

        assertThatThrownBy { check.require("x", RetainedData.KitData) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Could not read StorageClass x")
            .hasMessageContaining("forbidden")
    }

    @Test
    fun `a forbidden StorageClass read keeps the 403, so it is reported as Kubernetes permission-denied`() {
        val denial =
            KubernetesDenials.forbidden(
                "GET",
                "/apis/storage.k8s.io/v1/storageclasses/x",
                "storageclasses.storage.k8s.io \"x\" is forbidden: User \"alice\" cannot get resource \"storageclasses\" " +
                    "in API group \"storage.k8s.io\" at the cluster scope",
            )
        whenever(k8sService.storageClassReclaimPolicy("x")).thenReturn(Result.failure(denial))

        val thrown = catchThrowable { check.require("x", RetainedData.KitData) }

        assertThat(thrown).hasMessageContaining("Could not read StorageClass x").hasCause(denial)
        val event = KubernetesDenials.reported(thrown) as Event.Command.ProviderError
        assertThat(event.kind).isEqualTo(ProviderErrorKind.PERMISSION_DENIED)
        assertThat(event.request?.resource).isEqualTo("storageclasses")
    }
}
