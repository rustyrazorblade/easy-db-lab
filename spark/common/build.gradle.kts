plugins {
    `java-library`
}

dependencies {
    // Cassandra Driver for CQL setup
    api("org.apache.cassandra:java-driver-core:4.19.2")

    // Test dependencies
    testImplementation(libs.bundles.testcontainers)
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.25.3")
    // Spark is compileOnly (provided on EMR), but needed for unit testing SparkJobConfig.
    // Version variables are set by spark/build.gradle.kts subprojects block.
    testImplementation("org.apache.spark:spark-core_2.12:3.5.7")
    testImplementation("org.apache.spark:spark-sql_2.12:3.5.7")
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
