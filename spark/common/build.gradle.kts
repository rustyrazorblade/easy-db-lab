plugins {
    `java-library`
}

val sparkVersion: String by extra
val scalaVersion: String by extra

dependencies {
    // Cassandra Driver for CQL setup
    api(libs.cassandra.driver.core)

    // Test dependencies
    testImplementation(libs.bundles.testcontainers)
    testImplementation(libs.junit.jupiter.all)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
    // Java test classes use mockito-core directly (not mockito-kotlin)
    testImplementation(libs.mockito.core)
    // Spark is compileOnly (provided on EMR), but needed for unit testing SparkJobConfig.
    // Version variables are set by spark/build.gradle.kts subprojects block.
    testImplementation("org.apache.spark:spark-core_${scalaVersion}:${sparkVersion}")
    testImplementation("org.apache.spark:spark-sql_${scalaVersion}:${sparkVersion}")
}

tasks.test {
    useJUnitPlatform()
    // Spark integration tests need more time
    systemProperty("junit.jupiter.execution.timeout.default", "10m")
    // Pass project root so integration tests don't rely on fragile parent traversal
    systemProperty("project.root", rootProject.projectDir.absolutePath)
    // Integration tests need the connector-writer shadow JAR
    dependsOn(":spark:connector-writer:shadowJar")
}
