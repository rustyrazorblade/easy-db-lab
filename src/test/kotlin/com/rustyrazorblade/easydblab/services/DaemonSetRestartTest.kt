package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * A DaemonSet rollout restart is the `kubectl.kubernetes.io/restartedAt` annotation on its pod
 * template. The edit must land whatever the template's metadata looks like — a template with no
 * metadata used to be edited into nothing, so the restart silently never happened.
 */
class DaemonSetRestartTest {
    private val now = Instant.parse("2026-09-22T10:15:30Z")

    private fun restartedAt(ds: DaemonSet): String? =
        withRestartedAt(ds, now)
            .spec
            .template
            .metadata
            ?.annotations
            ?.get(RESTARTED_AT_ANNOTATION)

    @Test
    fun `a template without metadata gets the restart annotation`() {
        val ds =
            DaemonSetBuilder()
                .withNewMetadata()
                .withName("otel-collector")
                .endMetadata()
                .withNewSpec()
                .withNewTemplate()
                .withNewSpec()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build()

        assertThat(restartedAt(ds)).isEqualTo("2026-09-22T10:15:30Z")
    }

    @Test
    fun `existing template annotations are kept alongside the restart annotation`() {
        val ds =
            DaemonSetBuilder()
                .withNewMetadata()
                .withName("otel-collector")
                .endMetadata()
                .withNewSpec()
                .withNewTemplate()
                .withNewMetadata()
                .addToAnnotations("checksum/config", "abc")
                .addToAnnotations(RESTARTED_AT_ANNOTATION, "2020-01-01T00:00:00Z")
                .endMetadata()
                .endTemplate()
                .endSpec()
                .build()

        val annotations = withRestartedAt(ds, now).spec.template.metadata.annotations

        assertThat(annotations)
            .containsEntry("checksum/config", "abc")
            .containsEntry(RESTARTED_AT_ANNOTATION, "2026-09-22T10:15:30Z")
    }

    @Test
    fun `a DaemonSet without a pod template fails clearly instead of doing nothing`() {
        val ds =
            DaemonSetBuilder()
                .withNewMetadata()
                .withName("otel-collector")
                .withNamespace("default")
                .endMetadata()
                .build()

        assertThatThrownBy { withRestartedAt(ds, now) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("default/otel-collector")
    }
}
