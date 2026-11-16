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
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimSpec
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import java.io.File

/**
 * Every claim a kit creates — its own, a StatefulSet's, or one an operator (CNPG, Strimzi,
 * Altinity, TiDB) generates from the kit's resource — names the environment's kit StorageClass and
 * requests the environment's default size, with no selector and no PV name. On local volumes the
 * class is the kit's own `edl-local-<kit>`, so a claim can only bind its own kit's PVs; on a
 * provisioned class the cluster makes the volume. A fixed class, a class-less claim (the cluster
 * default, which may delete), or a selector a provisioner cannot satisfy fails here.
 */
class KitClaimStorageTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { TemplateService(get(), get()) }
                single { KitSourcesProvider(get()) }
                single { InstallTemplateResolver(get(), get()) }
            },
        )

    /** One claim as a kit's rendered manifest asks for it. */
    private data class Claim(
        val owner: String,
        val storageClass: String?,
        val size: String?,
        val selector: Map<String, String>,
        val volumeName: String?,
    )

    private fun kits(): List<KitConfig> {
        val resolver = get<InstallTemplateResolver>()
        return resolver
            .listAvailableTemplates()
            .mapNotNull { resolver.loadInstallConfig(resolver.resolve(it)) }
    }

    private fun templateFiles(kit: KitConfig): List<String> {
        val resolver = get<InstallTemplateResolver>()
        return resolver
            .listTemplateFiles(resolver.resolve(kit.name))
            .map { it.name }
            .filter { it.endsWith(".yaml.template") && "/" !in it }
    }

    /** Every manifest template of [kit], rendered with [kitStorageClass] recorded at init (null is local volumes). */
    private fun renderedObjects(
        kit: KitConfig,
        kitStorageClass: String?,
    ): List<HasMetadata> {
        val fixture =
            BuiltinKitFixture(
                kit.name,
                TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()),
                kitStorageClass,
            )
        // Helm values files (presto, trino) are not manifests; the raw class scan still reads them.
        return templateFiles(kit)
            .filter { MANIFEST_KIND.containsMatchIn(fixture.resource(it)) }
            .flatMap { fixture.render(it, INSTALL_TIME_VARS) }
    }

    private fun claims(obj: HasMetadata): List<Claim> {
        val owner = "${obj.kind}/${obj.metadata.name}"
        return when (obj) {
            is PersistentVolumeClaim -> listOf(claim(owner, obj.spec))
            is StatefulSet ->
                obj.spec.volumeClaimTemplates
                    .orEmpty()
                    .map { claim("$owner/${it.metadata.name}", it.spec) }
            is GenericKubernetesResource -> operatorClaims(owner, obj)
            else -> emptyList()
        }
    }

    private fun claim(
        owner: String,
        spec: PersistentVolumeClaimSpec,
    ) = Claim(
        owner = owner,
        storageClass = spec.storageClassName,
        size =
            spec.resources
                ?.requests
                ?.get("storage")
                ?.toString(),
        selector =
            spec.selector
                ?.matchLabels
                .orEmpty(),
        volumeName = spec.volumeName,
    )

    /** A claim spec held as plain YAML maps inside an operator's custom resource. */
    private fun mapClaim(
        owner: String,
        spec: Map<*, *>,
    ) = Claim(
        owner = owner,
        storageClass = spec["storageClassName"] as String?,
        size = spec["resources"].asMap()["requests"].asMap()["storage"]?.toString(),
        selector = spec["selector"].asMap()["matchLabels"].asStrings(),
        volumeName = spec["volumeName"] as String?,
    )

    /** Claims the CNPG, Strimzi, Altinity and TiDB operators generate from the kit's custom resources. */
    private fun operatorClaims(
        owner: String,
        obj: GenericKubernetesResource,
    ): List<Claim> {
        val spec = obj.additionalProperties["spec"].asMap()
        return when (obj.kind) {
            "Cluster" -> {
                val storage = spec["storage"].asMap()
                val template = storage["pvcTemplate"].asMap()
                listOf(
                    Claim(
                        owner,
                        storage["storageClass"] as String?,
                        storage["size"]?.toString(),
                        template["selector"].asMap()["matchLabels"].asStrings(),
                        template["volumeName"] as String?,
                    ),
                )
            }
            "KafkaNodePool" ->
                spec["storage"].asMap()["volumes"].asList().map { volume ->
                    val v = volume.asMap()
                    Claim("$owner/volume-${v["id"]}", v["class"] as String?, v["size"]?.toString(), v["selector"].asStrings(), null)
                }
            "ClickHouseInstallation" ->
                spec["templates"].asMap()["volumeClaimTemplates"].asList().map { template ->
                    mapClaim("$owner/${template.asMap()["name"]}", template.asMap()["spec"].asMap())
                }
            "TidbCluster" -> tidbClaims(owner, spec)
            else -> emptyList()
        }
    }

    private fun tidbClaims(
        owner: String,
        spec: Map<*, *>,
    ): List<Claim> =
        TIDB_COMPONENTS.flatMap { component ->
            val c = spec[component].asMap()
            val own =
                if (c.isEmpty() || !c.containsKey("requests")) {
                    emptyList()
                } else {
                    listOf(
                        Claim(
                            "$owner/$component",
                            c["storageClassName"] as String?,
                            c["requests"].asMap()["storage"]?.toString(),
                            emptyMap(),
                            null,
                        ),
                    )
                }
            own + c["storageClaims"].asList().map { mapClaim("$owner/$component/storageClaim", it.asMap()) }
        }

    private fun storageViolations(
        kitStorageClass: String?,
        expectedSize: String,
    ): List<String> =
        kits().flatMap { kit ->
            val expectedClass = kitStorageClass ?: "${Constants.K8s.KIT_LOCAL_CLASS_PREFIX}${kit.name}"
            renderedObjects(kit, kitStorageClass).flatMap { claims(it) }.flatMap { claim ->
                listOfNotNull(
                    "${kit.name}: ${claim.owner} names class ${claim.storageClass}, not $expectedClass"
                        .takeIf { claim.storageClass != expectedClass },
                    "${kit.name}: ${claim.owner} requests ${claim.size}, not $expectedSize"
                        .takeIf { claim.size != expectedSize },
                    "${kit.name}: ${claim.owner} selects ${claim.selector}".takeIf { claim.selector.isNotEmpty() },
                    "${kit.name}: ${claim.owner} names PV ${claim.volumeName}".takeIf { claim.volumeName != null },
                )
            }
        }

    @Test
    fun `on local volumes every kit claim names the kit's own class at the 10Ti default`() {
        assertThat(storageViolations(kitStorageClass = null, expectedSize = Constants.K8s.LOCAL_KIT_STORAGE_SIZE)).isEmpty()
    }

    @Test
    fun `on a provisioned class every kit claim names that class at the provisioned default`() {
        assertThat(
            storageViolations(kitStorageClass = "gp3-retain", expectedSize = Constants.K8s.DEFAULT_PROVISIONED_KIT_STORAGE_SIZE),
        ).isEmpty()
    }

    @Test
    fun `every kit with a claim declares a kit-storage step`() {
        val claimingKits = kits().filter { kit -> renderedObjects(kit, null).any { claims(it).isNotEmpty() } }
        assertThat(claimingKits.map { it.name })
            .contains("cassandra-pods", "postgres", "kafka", "clickhouse", "neo4j", "memcached", "tidb", "ignite3")

        val undeclared =
            claimingKits
                .filterNot { kit -> (kit.install + kit.start).any { it is InstallStep.KitStorage } }
                .map { it.name }
        assertThat(undeclared).isEmpty()
    }

    @Test
    fun `no kit template names a fixed StorageClass`() {
        val fixed =
            kits().flatMap { kit ->
                val fixture =
                    BuiltinKitFixture(kit.name, TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))
                templateFiles(kit).flatMap { file ->
                    fixture
                        .resource(file)
                        .lines()
                        .filter { CLASS_KEY.containsMatchIn(it) && KIT_STORAGE_CLASS_PLACEHOLDER !in it }
                        .map { "${kit.name}/$file: ${it.trim()}" }
                }
            }
        assertThat(fixed).isEmpty()
    }

    @Test
    fun `operators keep the volumes when their resource or claims go away`() {
        val kafkaVolumes =
            renderedObjects(kits().single { it.name == "kafka" }, null)
                .filterIsInstance<GenericKubernetesResource>()
                .filter { it.kind == "KafkaNodePool" }
                .flatMap {
                    it.additionalProperties["spec"]
                        .asMap()["storage"]
                        .asMap()["volumes"]
                        .asList()
                }
        assertThat(kafkaVolumes).isNotEmpty
        assertThat(kafkaVolumes.map { it.asMap()["deleteClaim"] }).containsOnly(false)

        val tidb =
            renderedObjects(kits().single { it.name == "tidb" }, null)
                .filterIsInstance<GenericKubernetesResource>()
                .single { it.kind == "TidbCluster" }
        assertThat(tidb.additionalProperties["spec"].asMap()["pvReclaimPolicy"]).isEqualTo("Retain")
    }

    private companion object {
        /**
         * The variables `kit install` computes rather than reads from arg defaults (the postgres
         * extension's image, uid and ports, and a replica count defaulting to the db node count); their values do not affect a claim.
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
                "CASSANDRA_NODES" to "3",
            )

        val MANIFEST_KIND = Regex("""(?m)^kind:""")

        val TIDB_COMPONENTS = listOf("pd", "tikv", "tiflash")

        const val KIT_STORAGE_CLASS_PLACEHOLDER = "__KIT_STORAGE_CLASS__"

        /** A YAML key that names a StorageClass in a claim or an operator's storage block. */
        val CLASS_KEY = Regex("""^\s*-?\s*(storageClassName|storageClass|class):""")

        fun Any?.asMap(): Map<*, *> = this as? Map<*, *> ?: emptyMap<Any, Any>()

        fun Any?.asList(): List<*> = this as? List<*> ?: emptyList<Any>()

        fun Any?.asStrings(): Map<String, String> = asMap().entries.associate { (k, v) -> k.toString() to v.toString() }
    }
}
