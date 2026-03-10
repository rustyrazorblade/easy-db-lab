plugins {
    application
    id("com.gradleup.shadow")
}

val scalaVersion: String by extra
val analyticsDir: File by extra
val cassandraAnalyticsVersion: String by extra

dependencies {
    implementation(project(":spark:common"))

    // Cassandra Analytics - published modules (from local Maven)
    implementation("org.apache.cassandra:cassandra-analytics-core_spark3_$scalaVersion:$cassandraAnalyticsVersion")
    implementation("org.apache.cassandra:cassandra-bridge_spark3_$scalaVersion:$cassandraAnalyticsVersion")
    implementation("org.apache.cassandra:cassandra-analytics-spark-converter_spark3_$scalaVersion:$cassandraAnalyticsVersion")

    // Cassandra Analytics - internal modules not published to Maven (from build output)
    implementation(files("$analyticsDir/cassandra-five-zero/build/libs/five-zero.jar"))
    implementation(files("$analyticsDir/cassandra-five-zero-bridge/build/libs/five-zero-bridge.jar"))
    implementation(files("$analyticsDir/cassandra-five-zero-types/build/libs/five-zero-types.jar"))
    implementation(files("$analyticsDir/cassandra-analytics-spark-five-zero-converter/build/libs/five-zero-sparksql.jar"))

    // Guava 32.x - cassandra-analytics requires RangeMap which was added in Guava 14+
    // Explicitly add to ensure it's included in shadow JAR with proper relocation
    implementation("com.google.guava:guava:32.1.3-jre")
}

application {
    mainClass.set("com.rustyrazorblade.easydblab.spark.DirectBulkWriter")
}

tasks.shadowJar {
    archiveBaseName.set("bulk-writer-sidecar")
    archiveClassifier.set("")
    mergeServiceFiles()

    // Relocate Guava to avoid conflicts with EMR's older Guava version
    relocate("com.google.common", "shaded.com.google.common")
    relocate("com.google.thirdparty", "shaded.com.google.thirdparty")

    manifest {
        attributes["Main-Class"] = "com.rustyrazorblade.easydblab.spark.DirectBulkWriter"
    }
}

tasks.jar {
    enabled = false
    dependsOn(tasks.shadowJar)
}
