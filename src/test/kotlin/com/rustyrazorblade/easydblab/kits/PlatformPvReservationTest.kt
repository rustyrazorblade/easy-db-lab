package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import java.io.File

/**
 * Every kit's platform PVs share the `local-storage-wfc` class, so a claim that does not name its
 * own kit's PVs binds whichever PV is free: neo4j's claim took postgres-duckdb's PV, and
 * postgres-duckdb's took plain postgres's. `platform-pvs` labels each PV with its kit instance
 * ([Constants.PV_KIT_LABEL]); every claim a platform-pvs kit creates — its own, a StatefulSet's,
 * or one an operator generates from the kit's resource — must select that label, or name one of
 * the kit's PVs outright.
 */
class PlatformPvReservationTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { TemplateService(get(), get()) }
                single { KitSourcesProvider(get()) }
                single { InstallTemplateResolver(get(), get()) }
            },
        )

    /** How one claim picks its PV: by label selector, by PV name, or neither. */
    private data class Claim(
        val owner: String,
        val selector: Map<String, String>,
        val volumeName: String?,
    )

    private fun platformPvKits(): List<KitConfig> {
        val resolver = get<InstallTemplateResolver>()
        return resolver
            .listAvailableTemplates()
            .mapNotNull { resolver.loadInstallConfig(resolver.resolve(it)) }
            .filter { kit -> (kit.install + kit.start).any { it is InstallStep.PlatformPvs } }
    }

    /** Every manifest template of [kit], rendered from its arg defaults plus [INSTALL_TIME_VARS]. */
    private fun renderedObjects(kit: KitConfig): List<HasMetadata> {
        val resolver = get<InstallTemplateResolver>()
        val fixture =
            BuiltinKitFixture(kit.name, TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))
        return resolver
            .listTemplateFiles(resolver.resolve(kit.name))
            .map { it.name }
            .filter { it.endsWith(".yaml.template") && "/" !in it }
            .flatMap { fixture.render(it, INSTALL_TIME_VARS) }
    }

    private fun claims(obj: HasMetadata): List<Claim> {
        val owner = "${obj.kind}/${obj.metadata.name}"
        return when (obj) {
            is PersistentVolumeClaim ->
                listOf(
                    Claim(
                        owner,
                        obj.spec.selector
                            ?.matchLabels
                            .orEmpty(),
                        obj.spec.volumeName,
                    ),
                )
            is StatefulSet ->
                obj.spec.volumeClaimTemplates.orEmpty().map {
                    Claim(
                        "$owner/${it.metadata.name}",
                        it.spec.selector
                            ?.matchLabels
                            .orEmpty(),
                        it.spec.volumeName,
                    )
                }
            is GenericKubernetesResource -> operatorClaims(owner, obj)
            else -> emptyList()
        }
    }

    /** Claims the CNPG, Strimzi and Altinity operators generate from the kit's custom resources. */
    private fun operatorClaims(
        owner: String,
        obj: GenericKubernetesResource,
    ): List<Claim> {
        val spec = obj.additionalProperties["spec"].asMap()
        return when (obj.kind) {
            "Cluster" -> {
                val template = spec["storage"].asMap()["pvcTemplate"].asMap()
                listOf(Claim(owner, template["selector"].asMap()["matchLabels"].asStrings(), null))
            }
            "KafkaNodePool" ->
                spec["storage"].asMap()["volumes"].asList().map { volume ->
                    Claim("$owner/volume-${volume.asMap()["id"]}", volume.asMap()["selector"].asStrings(), null)
                }
            "ClickHouseInstallation" ->
                spec["templates"].asMap()["volumeClaimTemplates"].asList().map { template ->
                    val claimSpec = template.asMap()["spec"].asMap()
                    Claim(
                        "$owner/${template.asMap()["name"]}",
                        claimSpec["selector"].asMap()["matchLabels"].asStrings(),
                        claimSpec["volumeName"] as String?,
                    )
                }
            else -> emptyList()
        }
    }

    @Test
    fun `every claim a platform-pvs kit creates is reserved to its own kit's PVs`() {
        val kits = platformPvKits()
        assertThat(kits.map { it.name }).contains("postgres", "kafka", "clickhouse", "neo4j", "memcached")

        val unreserved =
            kits.flatMap { kit ->
                val pvNamePrefixes =
                    (kit.install + kit.start)
                        .filterIsInstance<InstallStep.PlatformPvs>()
                        .map { "${it.volumeClaimTemplateName}-${kit.name}-" }
                val kitClaims = renderedObjects(kit).flatMap { claims(it) }
                if (kitClaims.isEmpty()) {
                    listOf("${kit.name}: no claim found in its templates")
                } else {
                    kitClaims
                        .filterNot { claim ->
                            val byLabel = claim.selector[Constants.PV_KIT_LABEL] == kit.name
                            val byName = claim.volumeName != null && pvNamePrefixes.any { claim.volumeName.startsWith(it) }
                            byLabel || byName
                        }.map { "${kit.name}: ${it.owner} selects ${it.selector}, volumeName ${it.volumeName}" }
                }
            }

        assertThat(unreserved).isEmpty()
    }

    private companion object {
        /**
         * The variables `kit install` computes rather than reads from arg defaults (the postgres
         * extension's image, uid and ports); their values do not affect a claim.
         */
        val INSTALL_TIME_VARS =
            mapOf(
                "IMAGE" to "ghcr.io/cloudnative-pg/postgresql:17",
                "PG_MAJOR_VERSION" to "17",
                "POSTGRES_UID" to "26",
                "POSTGRES_GID" to "26",
                "SHARED_PRELOAD_LIBRARIES" to "[]",
                "POST_INIT_SQL" to "[]",
                "POSTGRES_PORT" to "30432",
                "METRICS_PORT" to "30987",
            )

        fun Any?.asMap(): Map<*, *> = this as? Map<*, *> ?: emptyMap<Any, Any>()

        fun Any?.asList(): List<*> = this as? List<*> ?: emptyList<Any>()

        fun Any?.asStrings(): Map<String, String> = asMap().entries.associate { (k, v) -> k.toString() to v.toString() }
    }
}
