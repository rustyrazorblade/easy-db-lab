plugins {
    java
    application
    id("com.gradleup.shadow")
}

val sparkVersion = "3.5.7"
val scalaVersion = "2.12"
val cassandraAnalyticsVersion = "0.3-SNAPSHOT"

// Path to cassandra-analytics build output for modules not published to Maven
val analyticsDir = rootProject.projectDir.resolve(".cassandra-analytics")

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // Shared data generation module
    implementation(project(":spark-shared"))

    // Spark (provided scope for EMR - these are on the cluster)
    compileOnly("org.apache.spark:spark-core_$scalaVersion:$sparkVersion")
    compileOnly("org.apache.spark:spark-sql_$scalaVersion:$sparkVersion")

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
    // Default main class - can be overridden at runtime
    mainClass.set("com.rustyrazorblade.easydblab.spark.DirectBulkWriter")
}

// Use shadow plugin for fat JAR - properly merges META-INF/services files
tasks.shadowJar {
    archiveBaseName.set("bulk-writer")
    archiveClassifier.set("")
    mergeServiceFiles()

    // Relocate Guava to avoid conflicts with EMR's older Guava version
    // EMR ships with Guava 14.x but cassandra-analytics needs 30+
    relocate("com.google.common", "shaded.com.google.common")
    relocate("com.google.thirdparty", "shaded.com.google.thirdparty")

    manifest {
        attributes["Main-Class"] = "com.rustyrazorblade.easydblab.spark.DirectBulkWriter"
    }
}

// Replace default jar with shadowJar
tasks.jar {
    enabled = false
    dependsOn(tasks.shadowJar)
}
