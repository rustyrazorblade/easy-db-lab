// Plugins, cassandra-analytics deps, Guava relocation, and shadow config
// are applied by the parent spark/build.gradle.kts for all bulk-writer-* modules.

application {
    mainClass.set("com.rustyrazorblade.easydblab.spark.S3BulkWriter")
}

dependencies {
    // AWS SDK v2 for credential and region auto-detection
    implementation("software.amazon.awssdk:auth:2.42.23")
    implementation("software.amazon.awssdk:regions:2.42.23")

    // Test dependencies
    testImplementation(libs.bundles.testcontainers)
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.apache.spark:spark-core_2.12:3.5.7")
    testImplementation("org.apache.spark:spark-sql_2.12:3.5.7")
    // AWS SDK S3 for LocalStack testing
    testImplementation("software.amazon.awssdk:s3:2.42.23")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("junit.jupiter.execution.timeout.default", "10m")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("bulk-writer-s3")
    manifest {
        attributes["Main-Class"] = "com.rustyrazorblade.easydblab.spark.S3BulkWriter"
    }
}
