package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import com.rustyrazorblade.easydblab.configuration.tenancy.TestScopes
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding
import io.fabric8.kubernetes.api.model.rbac.RoleBinding
import io.fabric8.kubernetes.api.model.rbac.Subject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Every workload builder renders at the scope it is given and nowhere else (issue 1094, AC1 and AC3):
 * at `edl-ns` every namespaced object, binding subject and in-cluster address names `edl-ns`, and
 * every cluster-scoped object carries the cluster id, so two clusters on one API server never share
 * one. At the EC2 scope everything stays in `default`.
 */
class InstallScopeRenderTest : BaseKoinTest() {
    private companion object {
        const val CLUSTER_ID = "render-id"
        val CLUSTER_SCOPED_KINDS =
            setOf("ClusterRole", "ClusterRoleBinding", "StorageClass", "PersistentVolume", "Namespace", "PriorityClass")
    }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "test", clusterId = CLUSTER_ID, s3Bucket = "acct"))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    private val scope = InstallScope(K8sNamespace("edl-ns"), CLUSTER_ID)

    private fun resources(at: InstallScope): List<HasMetadata> = AllBuilderManifests.render(getKoin().get(), at).values.flatten()

    private fun subjects(resources: List<HasMetadata>): List<Subject> =
        resources.filterIsInstance<RoleBinding>().flatMap { it.subjects } +
            resources.filterIsInstance<ClusterRoleBinding>().flatMap { it.subjects }

    @Test
    fun `at another namespace every namespaced object is in it`() {
        val namespaced = resources(scope).filterNot { it.kind in CLUSTER_SCOPED_KINDS }

        assertThat(namespaced).isNotEmpty()
        assertThat(
            namespaced,
        ).allSatisfy { assertThat(it.metadata.namespace).describedAs("${it.kind} ${it.metadata.name}").isEqualTo("edl-ns") }
    }

    @Test
    fun `at another namespace every binding subject names it`() {
        val subjects = subjects(resources(scope)).filter { it.kind == "ServiceAccount" }

        assertThat(subjects).isNotEmpty()
        assertThat(subjects).allSatisfy { assertThat(it.namespace).describedAs(it.name).isEqualTo("edl-ns") }
    }

    @Test
    fun `every cluster-scoped object is named for the cluster, and every ClusterRoleBinding refers to its cluster's role`() {
        val all = resources(scope)
        val clusterScoped = all.filter { it.kind in CLUSTER_SCOPED_KINDS }

        assertThat(clusterScoped).isNotEmpty()
        assertThat(clusterScoped).allSatisfy { assertThat(it.metadata.name).describedAs(it.kind).endsWith("-$CLUSTER_ID") }
        assertThat(all.filterIsInstance<ClusterRoleBinding>()).allSatisfy { assertThat(it.roleRef.name).endsWith("-$CLUSTER_ID") }
    }

    @Test
    fun `no rendered configuration addresses a Service in the default namespace`() {
        val data = resources(scope).filterIsInstance<ConfigMap>().flatMap { it.data.orEmpty().values }

        assertThat(data).isNotEmpty()
        assertThat(data).noneSatisfy { assertThat(it).contains(".default.svc") }
        assertThat(data.joinToString("\n")).contains(".edl-ns.svc")
    }

    @Test
    fun `at the EC2 scope every namespaced object and subject stays in default`() {
        val all = resources(TestScopes.EC2)

        assertThat(all.filterNot { it.kind in CLUSTER_SCOPED_KINDS }).allSatisfy { assertThat(it.metadata.namespace).isEqualTo("default") }
        assertThat(subjects(all).filter { it.kind == "ServiceAccount" }).allSatisfy { assertThat(it.namespace).isEqualTo("default") }
    }
}
