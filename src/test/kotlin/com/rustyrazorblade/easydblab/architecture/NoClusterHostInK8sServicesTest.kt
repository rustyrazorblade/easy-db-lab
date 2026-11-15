package com.rustyrazorblade.easydblab.architecture

import com.rustyrazorblade.easydblab.architecture.fixtures.K8sServiceDependentWithHost
import com.rustyrazorblade.easydblab.architecture.fixtures.K8sServiceDependentWithoutHost
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Kubernetes services reach the cluster through the recorded kubeconfig and context (`KubeApi`),
 * never through a control host. A `services` class that depends on the Kubernetes client, the client
 * provider or the K8s service abstractions must not take a `ClusterHost` in any method or
 * constructor, unless it is on [ALLOW_LIST] because it also uses the host for SSH or its IP.
 *
 * The allow-list only shrinks: an entry that no longer depends on the client, or no longer takes a
 * host, fails the test so it is removed.
 */
class NoClusterHostInK8sServicesTest {
    private val mainClasses: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages(ROOT)

    @Test
    fun `no Kubernetes-dependent services class takes a ClusterHost outside the allow-list`() {
        assertThat(violations(mainClasses, "$ROOT.services", ALLOW_LIST)).isEmpty()
    }

    @Test
    fun `every allow-list entry still depends on the client and takes a ClusterHost`() {
        assertThat(staleEntries(mainClasses, ALLOW_LIST)).isEmpty()
    }

    @Test
    fun `the rule reports a Kubernetes-dependent class that takes a ClusterHost`() {
        val classes = ClassFileImporter().importClasses(K8sServiceDependentWithHost::class.java)

        assertThat(violations(classes, FIXTURES, emptySet()))
            .singleElement()
            .asString()
            .contains("K8sServiceDependentWithHost")
            .contains("doWork")
    }

    @Test
    fun `the rule accepts an allow-listed class that takes a ClusterHost`() {
        val classes = ClassFileImporter().importClasses(K8sServiceDependentWithHost::class.java)

        assertThat(violations(classes, FIXTURES, setOf(K8sServiceDependentWithHost::class.java.name))).isEmpty()
    }

    @Test
    fun `an allow-list entry that no longer takes a ClusterHost is stale`() {
        val classes = ClassFileImporter().importClasses(K8sServiceDependentWithoutHost::class.java)
        val entry = K8sServiceDependentWithoutHost::class.java.name

        assertThat(staleEntries(classes, setOf(entry))).containsExactly(entry)
    }

    @Test
    fun `an allow-list entry naming a class that does not exist is stale`() {
        val classes = ClassFileImporter().importClasses(K8sServiceDependentWithHost::class.java)

        assertThat(staleEntries(classes, setOf("$ROOT.services.Gone"))).containsExactly("$ROOT.services.Gone")
    }

    /** Each code unit in [packagePrefix] that takes a ClusterHost in a Kubernetes-dependent class off [allowList]. */
    private fun violations(
        classes: JavaClasses,
        packagePrefix: String,
        allowList: Set<String>,
    ): List<String> =
        classes
            .filter { it.packageName == packagePrefix || it.packageName.startsWith("$packagePrefix.") }
            .filter { outerName(it) !in allowList && dependsOnKubernetes(it) }
            .flatMap { hostTakingUnits(it) }

    /** Allow-list entries that name no class, or a class that no longer depends on Kubernetes or takes a host. */
    private fun staleEntries(
        classes: JavaClasses,
        allowList: Set<String>,
    ): List<String> =
        allowList.filterNot { entry ->
            val family = classes.filter { outerName(it) == entry }
            family.any { dependsOnKubernetes(it) } && family.any { hostTakingUnits(it).isNotEmpty() }
        }

    private fun dependsOnKubernetes(type: JavaClass): Boolean =
        type.directDependenciesFromSelf.any { it.targetClass.name in KUBERNETES_TYPES }

    // A lambda that captures a host (a log line naming a db node) compiles to a generated method
    // (synthetic, or named `...$lambda$N`) taking it; that is not a declared parameter.
    private fun hostTakingUnits(type: JavaClass): List<String> =
        type.codeUnits
            .filterNot { JavaModifier.SYNTHETIC in it.modifiers || "\$lambda" in it.name }
            .filter { unit -> unit.rawParameterTypes.any { it.name == CLUSTER_HOST } }
            .map { "${it.fullName} takes a ClusterHost (${it.sourceCodeLocation})" }

    /** A lambda or nested class counts as part of the top-level class it is declared in. */
    private fun outerName(type: JavaClass): String = type.name.substringBefore('$')

    private companion object {
        const val ROOT = "com.rustyrazorblade.easydblab"
        const val FIXTURES = "$ROOT.architecture.fixtures"
        const val CLUSTER_HOST = "$ROOT.configuration.ClusterHost"

        val KUBERNETES_TYPES =
            setOf(
                "io.fabric8.kubernetes.client.KubernetesClient",
                "$ROOT.services.K8sClientProvider",
                "$ROOT.services.K8sService",
                "$ROOT.services.K8sManifestOperations",
                "$ROOT.services.K8sJobOperations",
                "$ROOT.services.K8sNamespaceOperations",
                "$ROOT.services.K8sStorageOperations",
                "$ROOT.services.K8sPodOperations",
            )

        /**
         * Classes that reach Kubernetes and also use a control host for SSH or its IP. Only shrinks.
         * - `DefaultGrafanaDeployService` uploads the dashboard tree to the control node over SSH.
         * - `DefaultSidecarService` points the sidecar at the control node's private IP.
         */
        val ALLOW_LIST =
            setOf(
                "$ROOT.services.DefaultGrafanaDeployService",
                "$ROOT.services.DefaultSidecarService",
            )
    }
}
