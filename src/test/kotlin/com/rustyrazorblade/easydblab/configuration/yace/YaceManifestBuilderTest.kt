package com.rustyrazorblade.easydblab.configuration.yace

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Every cluster runs its own YACE, and each labels what it finds with its own cluster. When YACE
 * discovered every instance tagged `easy_cass_lab=1`, a cluster's Instance and Cloud dashboard
 * showed the other clusters' instances as its own, and `cluster` = All counted each instance once
 * per running cluster. Its per-cluster jobs now discover only resources tagged with this
 * cluster's `ClusterId`.
 */
class YaceManifestBuilderTest : BaseKoinTest() {
    private val state = ClusterState(name = "lab", versions = mutableMapOf())

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<ClusterStateManager>().also { whenever(it.load()).thenReturn(state) } }
                single { TemplateService(get(), get()) }
            },
        )

    /** Each job's type and its search tags, from the rendered config. */
    private fun searchTagsByJob(): Map<String, Map<String, String>> {
        val yaml = checkNotNull(YaceManifestBuilder(getKoin().get()).buildConfigMap().data["yace-config.yaml"])
        val discovery = checkNotNull((Yaml.default.parseToYamlNode(yaml) as YamlMap).get<YamlMap>("discovery"))
        return checkNotNull(discovery.get<YamlList>("jobs")).items.map { it as YamlMap }.associate { job ->
            val tags =
                checkNotNull(job.get<YamlList>("searchTags"))
                    .items
                    .map { it as YamlMap }
                    .associate { scalar(it, "key") to scalar(it, "value") }
            scalar(job, "type") to tags
        }
    }

    private fun scalar(
        map: YamlMap,
        key: String,
    ): String = checkNotNull(map.get<YamlScalar>(key)) { "no $key in $map" }.content

    /**
     * The series YACE writes for [type]'s job: `aws_<namespace>_<metric>_<statistic>`, with the
     * metric name split at each lower-to-upper case change and every other character made `_`.
     */
    private fun gathered(type: String): Set<String> {
        val yaml = checkNotNull(YaceManifestBuilder(getKoin().get()).buildConfigMap().data["yace-config.yaml"])
        val discovery = checkNotNull((Yaml.default.parseToYamlNode(yaml) as YamlMap).get<YamlMap>("discovery"))
        val job = checkNotNull(discovery.get<YamlList>("jobs")).items.map { it as YamlMap }.single { scalar(it, "type") == type }
        val prefix = "aws_" + type.substringAfter("/").lowercase()

        fun prom(name: String) = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").replace(Regex("[^A-Za-z0-9]"), "_").lowercase()
        return checkNotNull(job.get<YamlList>("metrics"))
            .items
            .map { it as YamlMap }
            .flatMap { metric ->
                checkNotNull(metric.get<YamlList>("statistics")).items.map { stat ->
                    "${prefix}_${prom(scalar(metric, "name"))}_${(stat as YamlScalar).content.lowercase()}"
                }
            }.toSet()
    }

    /** OpenSearch panels read p99 latencies, request sums and used space; YACE once gathered only averages. */
    @Test
    fun `every OpenSearch series a dashboard reads is one YACE gathers`() {
        val read =
            File("dashboards")
                .walkTopDown()
                .filter { it.extension == "json" }
                .flatMap { Regex("""aws_es_\w+""").findAll(it.readText()).map { match -> match.value } }
                .toSet()

        assertThat(read).isNotEmpty()
        assertThat(gathered("AWS/ES")).containsAll(read)
    }

    @Test
    fun `the instance, volume and OpenSearch jobs discover only this cluster's resources`() {
        val jobs = searchTagsByJob()

        for (type in listOf("AWS/EC2", "AWS/EBS", "AWS/ES")) {
            assertThat(jobs[type]).describedAs(type).containsEntry("ClusterId", state.clusterId).containsEntry("easy_cass_lab", "1")
        }
    }
}
