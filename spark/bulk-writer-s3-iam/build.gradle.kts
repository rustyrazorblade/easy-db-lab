// Plugins, cassandra-analytics deps, Guava relocation, and shadow config
// are applied by the parent spark/build.gradle.kts for all bulk-writer-* modules.

application {
    mainClass.set("com.rustyrazorblade.easydblab.spark.IamBulkWriter")
}

val sparkVersion: String by extra
val scalaVersion: String by extra

dependencies {
    // AWS SDK v2 for region auto-detection and S3 replication polling
    implementation(libs.awssdk.regions)
    implementation(libs.awssdk.s3)

    testImplementation(libs.junit.jupiter.all)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
    // Java test classes use mockito-core directly (not mockito-kotlin)
    testImplementation(libs.mockito.core)
    testImplementation("org.apache.spark:spark-core_${scalaVersion}:${sparkVersion}")
    testImplementation("org.apache.spark:spark-sql_${scalaVersion}:${sparkVersion}")
    testImplementation(libs.bundles.testcontainers)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("bulk-writer-s3-iam")
    archiveVersion.set("")
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.rustyrazorblade.easydblab.spark.IamBulkWriter"
    }
}
