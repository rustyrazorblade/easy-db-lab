package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.configuration.grafana.ClusterFilterGuards.Language
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every running cluster's YACE reports the shared account bucket, each copy labelled with its own
 * cluster. With `cluster` set to All a panel reading the AWS/S3 metrics drew the bucket once per
 * running cluster, and anything that added the series counted it that many times. Each such query
 * collapses the copies with `max by (...)` over the bucket's own labels, never over `cluster`, so
 * a bucket counts once and a single cluster still shows its own values.
 */
class SharedBucketCountTest {
    private val collapsed = Regex("""^max by \(([^)]*)\) \(""")

    @Test
    fun `every AWS S3 query counts each bucket once across clusters`() {
        val problems =
            DashboardFiles.all().flatMap { file ->
                ClusterFilterGuards
                    .queries(Json.parseToJsonElement(file.readText()).jsonObject)
                    .filter { it.language == Language.PROMQL && "aws_s3_" in it.text && !it.where.startsWith("variable ") }
                    .mapNotNull { query ->
                        val labels = collapsed.find(query.text.trim())?.groupValues?.get(1)?.split(",")?.map { it.trim() }
                        when {
                            labels == null -> "${file.path} ${query.where}: ${query.text} is not collapsed with max by (...)"
                            "cluster" in labels -> "${file.path} ${query.where}: ${query.text} keeps cluster"
                            "dimension_BucketName" !in labels -> "${file.path} ${query.where}: ${query.text} drops the bucket"
                            else -> null
                        }
                    }
            }

        assertThat(problems).isEmpty()
    }
}
